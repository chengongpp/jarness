package ch.predmet.jarness.core.config;

import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.config.JarnessLayout;
import ch.predmet.jarness.api.preset.Preset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loads {@link Preset}s from the classpath (builtins) and from {@code <base>/presets/*.toml}
 * (user presets). A user preset with the same name overrides the builtin.
 *
 * <p>Preset schema:</p>
 * <pre>{@code
 * name = "default"
 * description = "..."
 * mods = ["jarness.core"]
 * [instruments]
 * "jarness.core:tools.web" = true
 * [defaults]
 * template = "standard"
 * provider = "GB10"
 * model = "DeepSeek-V4-Flash"
 * }</pre>
 */
public final class PresetLoader {

    private PresetLoader() {
    }

    /**
     * Load the presets shipped with the runtime ({@code default} and {@code tui}).
     *
     * @return the builtin presets; {@code builtin=true}, {@code source=null}
     */
    public static List<Preset> builtins() {
        return List.of(builtin("default"), builtin("tui"));
    }

    private static Preset builtin(String name) {
        String resource = "/presets/" + name + ".toml";
        try (InputStream in = PresetLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("builtin preset resource missing from classpath: " + resource);
            }
            String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(TomlFiles.parse(toml), name, null, true);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read builtin preset " + resource, e);
        }
    }

    /**
     * Load one preset by name; the user file {@code <base>/presets/<name>.toml} wins over a
     * builtin of the same name.
     *
     * @param layout the base directory layout
     * @param name   the preset name
     * @return the preset, or empty when it exists neither as a user file nor as a builtin
     */
    public static Optional<Preset> load(JarnessLayout layout, String name) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(name, "name");
        Path userFile = layout.presetFile(name);
        if (Files.isRegularFile(userFile)) {
            return Optional.of(parse(TomlFiles.load(userFile), name, userFile, false));
        }
        return builtins().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /**
     * List every known preset: builtins plus every {@code *.toml} in {@code <base>/presets},
     * user presets overriding builtins by name.
     *
     * @param layout the base directory layout
     * @return all presets, builtins first in declaration order
     */
    public static List<Preset> all(JarnessLayout layout) {
        Objects.requireNonNull(layout, "layout");
        Map<String, Preset> byName = new LinkedHashMap<>();
        for (Preset preset : builtins()) {
            byName.put(preset.name(), preset);
        }
        if (Files.isDirectory(layout.presetsDir())) {
            List<Path> files;
            try (Stream<Path> stream = Files.list(layout.presetsDir())) {
                files = stream.filter(p -> Files.isRegularFile(p)
                                && p.getFileName().toString().endsWith(".toml"))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new IllegalStateException("cannot list presets directory " + layout.presetsDir(), e);
            }
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String fallbackName = fileName.substring(0, fileName.length() - ".toml".length());
                Preset preset = parse(TomlFiles.load(file), fallbackName, file, false);
                byName.put(preset.name(), preset);
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * Build a preset from a parsed preset document.
     *
     * @param view         the parsed preset TOML
     * @param fallbackName name used when the document has no {@code name} key
     * @param source       the file the document came from, or {@code null} for a builtin
     * @param builtin      whether this preset ships with the runtime
     * @return the preset
     */
    public static Preset parse(ConfigView view, String fallbackName, Path source, boolean builtin) {
        Objects.requireNonNull(view, "view");
        String name = view.string("name", fallbackName);
        String description = view.string("description", "");
        LinkedHashSet<String> mods = new LinkedHashSet<>(view.stringList("mods"));
        Map<String, Boolean> instruments = new LinkedHashMap<>();
        Object instrumentsTable = view.shallowMap().get("instruments");
        if (instrumentsTable instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue();
                boolean enabled = value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
                instruments.put(String.valueOf(entry.getKey()), enabled);
            }
        }
        String template = view.findString("defaults.template").orElse(null);
        String provider = view.findString("defaults.provider").orElse(null);
        String model = view.findString("defaults.model").orElse(null);
        return new Preset(name, description, mods, instruments, template, provider, model, source, builtin);
    }
}
