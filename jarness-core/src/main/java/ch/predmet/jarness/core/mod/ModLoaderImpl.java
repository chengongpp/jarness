package ch.predmet.jarness.core.mod;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.annotation.Mod;
import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.hook.event.ModEvent;
import ch.predmet.jarness.api.instrument.Instrument;
import ch.predmet.jarness.api.instrument.InstrumentContext;
import ch.predmet.jarness.api.instrument.InstrumentRegistry;
import ch.predmet.jarness.api.mod.JarnessMod;
import ch.predmet.jarness.api.mod.LoadReport;
import ch.predmet.jarness.api.mod.LoadedMod;
import ch.predmet.jarness.api.mod.ModDescriptor;
import ch.predmet.jarness.api.mod.ModLoadError;
import ch.predmet.jarness.api.mod.ModLoader;
import ch.predmet.jarness.api.mod.ModProvider;
import ch.predmet.jarness.api.preset.Preset;
import ch.predmet.jarness.core.config.TomlFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Default {@link ModLoader}: discovers system mods via {@link ServiceLoader} and jar mods in
 * {@code <base>/mods}, resolves dependencies with {@link ModResolver}, instantiates mods in load
 * order, and applies the enabled instruments.
 *
 * <p>The loader stays alive after the initial bake to support dynamic loading via
 * {@link #loadJar(Path, boolean)}; unloading is not supported. Jar class loaders are kept open
 * until {@link #close()}.</p>
 */
public final class ModLoaderImpl implements ModLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ModLoaderImpl.class);

    private final JarnessLayout layout;
    private final Supplier<JarnessRuntime> runtime;
    private final InstrumentRegistryImpl registry = new InstrumentRegistryImpl();
    private final List<LoadedMod> loaded = new ArrayList<>();
    private final Map<String, LoadedMod> loadedById = new LinkedHashMap<>();
    private final Map<String, ConfigView> modConfigs = new LinkedHashMap<>();
    private final List<URLClassLoader> jarLoaders = new ArrayList<>();
    private Preset activePreset;
    private LoadReport lastReport = LoadReport.empty();
    private boolean baked;
    private int nextOrder;

    /**
     * Create the loader.
     *
     * @param layout  the base directory layout
     * @param runtime supplies the running runtime; may yield {@code null} before construction
     */
    public ModLoaderImpl(JarnessLayout layout, Supplier<JarnessRuntime> runtime) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * @return the instrument registry holding every instrument this loader registered
     */
    public InstrumentRegistry instrumentRegistry() {
        return registry;
    }

    @Override
    public synchronized LoadReport loadAll(Preset preset) {
        long startNanos = System.nanoTime();
        this.activePreset = preset;
        List<ModLoadError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Instrument> applied = new ArrayList<>();
        List<LoadedMod> loadedNow = new ArrayList<>();

        // DISCOVERY
        List<ModResolver.Candidate> candidates = new ArrayList<>();
        Map<String, ModProvider> providers = new LinkedHashMap<>();
        discoverSystemMods(candidates, providers, errors);
        discoverJarMods(candidates, errors, warnings);

        // PRESET filter
        if (preset != null) {
            Iterator<ModResolver.Candidate> it = candidates.iterator();
            while (it.hasNext()) {
                ModResolver.Candidate candidate = it.next();
                ModDescriptor descriptor = candidate.descriptor();
                if (!preset.admitsMod(descriptor.id()) && !descriptor.required()) {
                    warnings.add("mod '" + descriptor.id() + "' skipped: not admitted by preset '"
                            + preset.name() + "'");
                    LOG.warn("mod '{}' skipped: not admitted by preset '{}'", descriptor.id(), preset.name());
                    it.remove();
                    providers.remove(descriptor.id());
                }
            }
        }

        // RESOLVE
        if (errors.isEmpty()) {
            ModResolver.Result result = ModResolver.resolve(candidates);
            if (!result.ok()) {
                errors.addAll(result.errors());
            } else {
                // INSTANTIATE + REGISTER in load order
                for (ModResolver.Candidate candidate : result.order()) {
                    LoadedMod lm = registerCandidate(candidate, providers.get(candidate.descriptor().id()),
                            preset, errors);
                    if (lm != null) {
                        loadedNow.add(lm);
                    }
                }
                // APPLY
                for (Instrument instrument : registry.enabled()) {
                    applyInstrument(instrument, errors, applied);
                }
            }
        }

        baked = errors.isEmpty();
        return finish(loadedNow, errors, warnings, applied, startNanos);
    }

    @Override
    public synchronized LoadReport loadJar(Path jar, boolean applyNow) {
        Objects.requireNonNull(jar, "jar");
        long startNanos = System.nanoTime();
        List<ModLoadError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Instrument> applied = new ArrayList<>();
        List<LoadedMod> loadedNow = new ArrayList<>();

        ModDescriptor descriptor = readJarDescriptor(jar, errors, warnings);
        if (descriptor != null) {
            if (loadedById.containsKey(descriptor.id())) {
                errors.add(new ModLoadError(descriptor.id(), jar, "RESOLVE",
                        "duplicate mod id '" + descriptor.id() + "': already loaded from "
                                + source(loadedById.get(descriptor.id())),
                        "", "a mod can only be loaded once; unloading is not supported"));
            } else {
                List<ModResolver.Candidate> all = new ArrayList<>();
                for (LoadedMod lm : loaded) {
                    all.add(new ModResolver.Candidate(lm.descriptor(), lm.file()));
                }
                ModResolver.Candidate candidate = new ModResolver.Candidate(descriptor, jar);
                all.add(candidate);
                ModResolver.Result result = ModResolver.resolve(all);
                if (!result.ok()) {
                    errors.addAll(result.errors());
                } else {
                    LoadedMod lm = registerCandidate(candidate, null, activePreset, errors);
                    if (lm != null) {
                        loadedNow.add(lm);
                        if (applyNow) {
                            for (Instrument instrument : registry.enabled()) {
                                if (instrument.modId().equals(descriptor.id())) {
                                    applyInstrument(instrument, errors, applied);
                                }
                            }
                        }
                    }
                }
            }
        }
        return finish(loadedNow, errors, warnings, applied, startNanos);
    }

    // ------------------------------------------------------------------ discovery

    private void discoverSystemMods(List<ModResolver.Candidate> candidates,
                                    Map<String, ModProvider> providers,
                                    List<ModLoadError> errors) {
        ServiceLoader<ModProvider> serviceLoader;
        try {
            serviceLoader = ServiceLoader.load(ModProvider.class, ModProvider.class.getClassLoader());
        } catch (Throwable t) {
            errors.add(error("<runtime>", null, "DISCOVERY",
                    "failed to initialize ModProvider services", t,
                    "check META-INF/services/ch.predmet.jarness.api.mod.ModProvider declarations"));
            return;
        }
        Iterator<ModProvider> it = serviceLoader.iterator();
        while (true) {
            ModProvider provider;
            try {
                if (!it.hasNext()) {
                    break;
                }
            } catch (Throwable t) {
                errors.add(error("<runtime>", null, "DISCOVERY",
                        "ModProvider service enumeration failed", t,
                        "check META-INF/services/ch.predmet.jarness.api.mod.ModProvider declarations"));
                break;
            }
            try {
                provider = it.next();
            } catch (Throwable t) {
                errors.add(error("<runtime>", null, "DISCOVERY",
                        "a ModProvider service failed to load and was skipped", t,
                        "check META-INF/services/ch.predmet.jarness.api.mod.ModProvider declarations"));
                continue;
            }
            ModDescriptor descriptor;
            try {
                descriptor = provider.descriptor();
            } catch (Throwable t) {
                errors.add(error("<runtime>", null, "DISCOVERY",
                        "ModProvider " + provider.getClass().getName() + " failed to supply a descriptor",
                        t, "fix or remove the provider"));
                continue;
            }
            candidates.add(new ModResolver.Candidate(descriptor, null));
            providers.put(descriptor.id(), provider);
        }
    }

    private void discoverJarMods(List<ModResolver.Candidate> candidates,
                                 List<ModLoadError> errors, List<String> warnings) {
        Path modsDir = layout.modsDir();
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        List<Path> jars;
        try (Stream<Path> stream = Files.list(modsDir)) {
            jars = stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            errors.add(error("<runtime>", modsDir, "DISCOVERY",
                    "cannot list mods directory", e, "check directory permissions"));
            return;
        }
        for (Path jar : jars) {
            ModDescriptor descriptor = readJarDescriptor(jar, errors, warnings);
            if (descriptor != null) {
                candidates.add(new ModResolver.Candidate(descriptor, jar));
            }
        }
    }

    /** @return the descriptor, or {@code null} when the jar is not a jarness mod */
    private ModDescriptor readJarDescriptor(Path jar, List<ModLoadError> errors, List<String> warnings) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry("META-INF/jnmods.toml");
            if (entry == null) {
                warnings.add(jar + ": not a jarness mod, skipped");
                LOG.warn("{}: not a jarness mod, skipped", jar);
                return null;
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                return JnmodsParser.parse(in);
            }
        } catch (Throwable t) {
            errors.add(error("<runtime>", jar, "PARSE",
                    "failed to read META-INF/jnmods.toml", t,
                    "fix the mod descriptor or remove the jar"));
            return null;
        }
    }

    // ------------------------------------------------------------------ instantiate + register

    private static boolean apiCompatible(String apiVersion) {
        String major = apiVersion == null ? "" : apiVersion.split("\\.")[0].trim();
        return major.isEmpty() || major.equals(ModDescriptor.API_VERSION);
    }

    private LoadedMod registerCandidate(ModResolver.Candidate candidate, ModProvider provider,
                                        Preset preset, List<ModLoadError> errors) {
        ModDescriptor descriptor = candidate.descriptor();
        String modId = descriptor.id();
        Path file = candidate.file();

        if (!apiCompatible(descriptor.apiVersion())) {
            errors.add(error(modId, file, "API",
                    "mod targets API version " + descriptor.apiVersion()
                            + ", runtime implements " + ModDescriptor.API_VERSION,
                    null, "rebuild the mod against the current jarness-api"));
            return null;
        }

        JarnessMod instance;
        try {
            if (provider != null) {
                instance = Objects.requireNonNull(provider.mod(), "provider returned null mod instance");
            } else {
                URLClassLoader classLoader = new URLClassLoader(
                        new URL[]{file.toUri().toURL()}, JarnessMod.class.getClassLoader());
                try {
                    Class<?> modClass = resolveModClass(classLoader, file, descriptor);
                    var ctor = modClass.getDeclaredConstructor();
                    ctor.trySetAccessible();
                    instance = (JarnessMod) ctor.newInstance();
                } catch (Throwable t) {
                    try {
                        classLoader.close();
                    } catch (IOException ignored) {
                        // nothing sensible to do
                    }
                    throw t;
                }
                jarLoaders.add(classLoader);
            }
        } catch (Throwable t) {
            errors.add(error(modId, file, "INSTANTIATE",
                    "failed to instantiate the mod entrypoint", t,
                    "check the entrypoint class and its public no-arg constructor"));
            return null;
        }

        ConfigView modConfig;
        try {
            modConfig = TomlFiles.load(layout.modConfigFile(modId));
        } catch (Throwable t) {
            errors.add(error(modId, file, "PARSE",
                    "failed to parse mod config " + layout.modConfigFile(modId), t,
                    "fix the TOML syntax of the mod config"));
            return null;
        }

        InstrumentRegistrarImpl registrar = new InstrumentRegistrarImpl(modId);
        ModContextImpl context = new ModContextImpl(descriptor, layout, runtime, file, registrar);
        try {
            instance.register(context);
            List<Instrument> instruments = new ArrayList<>(registrar.drain());
            instruments.addAll(scanInstrumentMethods(instance, modId));

            int order = nextOrder++;
            registry.noteModOrder(modId, order);
            for (Instrument instrument : instruments) {
                registry.register(instrument);
                if (preset != null) {
                    registry.setEnabled(instrument.name(),
                            preset.enablesInstrument(modId, instrument.name(), instrument.autoEnable()));
                }
            }

            LoadedMod loadedMod = new LoadedMod(descriptor, file, order);
            loaded.add(loadedMod);
            loadedById.put(modId, loadedMod);
            modConfigs.put(modId, modConfig);
            emitHook(HookPoint.MOD_LOADED, ModEvent.of(loadedMod));
            return loadedMod;
        } catch (Throwable t) {
            errors.add(error(modId, file, "REGISTER",
                    "mod.register() failed", t,
                    "check the mod's register method and its @Instrument method signatures"));
            return null;
        }
    }

    private Class<?> resolveModClass(URLClassLoader classLoader, Path jar, ModDescriptor descriptor)
            throws Exception {
        if (descriptor.entrypoint() != null && !descriptor.entrypoint().isBlank()) {
            Class<?> cls = Class.forName(descriptor.entrypoint(), false, classLoader);
            if (!JarnessMod.class.isAssignableFrom(cls)) {
                throw new IllegalStateException("entrypoint " + descriptor.entrypoint()
                        + " does not implement " + JarnessMod.class.getName());
            }
            return cls;
        }
        List<Class<?>> found = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            for (JarEntry entry : Collections.list(jarFile.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.equals("module-info.class")) {
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                Class<?> cls;
                try {
                    cls = Class.forName(className, false, classLoader);
                } catch (Throwable t) {
                    continue; // unresolvable classes cannot be the entrypoint
                }
                Mod annotation = cls.getAnnotation(Mod.class);
                if (annotation != null && JarnessMod.class.isAssignableFrom(cls)
                        && !cls.isInterface() && !Modifier.isAbstract(cls.getModifiers())) {
                    found.add(cls);
                }
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("descriptor declares no entrypoint and the jar contains no "
                    + "@Mod-annotated JarnessMod implementation; add 'entrypoint' to META-INF/jnmods.toml "
                    + "or annotate exactly one mod class with @Mod");
        }
        if (found.size() > 1) {
            throw new IllegalStateException("multiple @Mod-annotated entrypoints found: " + found.stream()
                    .map(Class::getName).toList()
                    + "; declare a single 'entrypoint' in META-INF/jnmods.toml");
        }
        Class<?> cls = found.get(0);
        Mod annotation = cls.getAnnotation(Mod.class);
        if (annotation != null && !annotation.value().isBlank() && !annotation.value().equals(descriptor.id())) {
            throw new IllegalStateException("@Mod id '" + annotation.value()
                    + "' does not match descriptor id '" + descriptor.id() + "'");
        }
        return cls;
    }

    private List<Instrument> scanInstrumentMethods(JarnessMod instance, String modId) {
        List<Instrument> instruments = new ArrayList<>();
        for (Method method : instance.getClass().getDeclaredMethods()) {
            ch.predmet.jarness.api.annotation.Instrument annotation =
                    method.getAnnotation(ch.predmet.jarness.api.annotation.Instrument.class);
            if (annotation == null) {
                continue;
            }
            boolean valid = !Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == InstrumentContext.class
                    && method.getReturnType() == void.class;
            if (!valid) {
                throw new IllegalStateException("@Instrument method " + method.getName()
                        + " of " + instance.getClass().getName()
                        + " must be an instance method taking one InstrumentContext parameter and returning void");
            }
            method.trySetAccessible();
            instruments.add(new MethodInstrument(modId, annotation, instance, method));
        }
        return instruments;
    }

    // ------------------------------------------------------------------ apply

    private void applyInstrument(Instrument instrument, List<ModLoadError> errors, List<Instrument> applied) {
        LoadedMod lm = loadedById.get(instrument.modId());
        InstrumentContextImpl context = new InstrumentContextImpl(runtime, instrument.modId(), instrument,
                modConfigs.getOrDefault(instrument.modId(), ConfigView.empty()));
        try {
            instrument.apply(context);
            applied.add(instrument);
            if (lm != null) {
                emitHook(HookPoint.INSTRUMENT_APPLIED, ModEvent.of(lm, instrument));
            }
        } catch (Throwable t) {
            errors.add(error(instrument.modId(), lm != null ? lm.file() : null, "APPLY",
                    "instrument '" + instrument.key() + "' failed to apply", t,
                    "disable '" + instrument.key() + "' in the preset or fix the mod"));
        }
    }

    // ------------------------------------------------------------------ bookkeeping

    private LoadReport finish(List<LoadedMod> loadedNow, List<ModLoadError> errors, List<String> warnings,
                              List<Instrument> applied, long startNanos) {
        LoadReport report = new LoadReport(loadedNow, errors, warnings, applied,
                Duration.ofNanos(System.nanoTime() - startNanos));
        this.lastReport = report;
        emitHook(HookPoint.MODS_LOADED, report);
        return report;
    }

    private void emitHook(HookPoint point, Object payload) {
        try {
            JarnessRuntime rt = runtime.get();
            if (rt != null && rt.hooks() != null) {
                rt.hooks().emit(point, payload);
            }
        } catch (Throwable t) {
            LOG.warn("failed to emit hook {}: {}", point, t.toString());
        }
    }

    private static String source(LoadedMod lm) {
        return lm.file() != null ? lm.file().toString() : "classpath";
    }

    private static ModLoadError error(String modId, Path file, String phase, String message,
                                      Throwable t, String hint) {
        String detail = t == null ? ""
                : t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        return new ModLoadError(modId, file, phase, message, detail, hint == null ? "" : hint);
    }

    // ------------------------------------------------------------------ accessors

    @Override
    public synchronized List<LoadedMod> loaded() {
        return List.copyOf(loaded);
    }

    @Override
    public List<Instrument> instruments() {
        return registry.all();
    }

    @Override
    public Optional<Instrument> instrument(String name) {
        return registry.byName(name);
    }

    @Override
    public boolean enabled(String instrumentName) {
        return registry.isEnabled(instrumentName);
    }

    @Override
    public synchronized Optional<Preset> preset() {
        return Optional.ofNullable(activePreset);
    }

    @Override
    public synchronized LoadReport lastReport() {
        return lastReport;
    }

    @Override
    public synchronized boolean baked() {
        return baked;
    }

    @Override
    public synchronized void close() {
        for (URLClassLoader classLoader : jarLoaders) {
            try {
                classLoader.close();
            } catch (IOException e) {
                LOG.warn("failed to close mod class loader", e);
            }
        }
        jarLoaders.clear();
    }

    /** An {@code @Instrument}-annotated method wrapped as an {@link Instrument}. */
    private record MethodInstrument(String modId, String name, boolean autoEnable, int priority,
                                    String description, JarnessMod instance, Method method)
            implements Instrument {

        MethodInstrument(String modId, ch.predmet.jarness.api.annotation.Instrument annotation,
                         JarnessMod instance, Method method) {
            this(modId, annotation.value(), annotation.autoEnable(), annotation.priority(),
                    annotation.description(), instance, method);
        }

        @Override
        public void apply(InstrumentContext ctx) {
            try {
                method.invoke(instance, ctx);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot invoke @Instrument method " + method.getName(), e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(cause);
            }
        }
    }
}
