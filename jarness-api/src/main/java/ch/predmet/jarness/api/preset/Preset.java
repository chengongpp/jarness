package ch.predmet.jarness.api.preset;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A preset is the answer to "which mods, and which of their instruments, are active".
 *
 * <p>Loading rules:</p>
 * <ul>
 *   <li>An empty {@link #mods()} set means "load every discovered mod"; otherwise only the named
 *       mods load, except mods whose descriptor marks them {@code required}.</li>
 *   <li>An instrument that the preset does not mention keeps the mod author's {@code autoEnable}
 *       policy; an explicit {@code true} force-enables a manual instrument, an explicit
 *       {@code false} disables an automatic one.</li>
 * </ul>
 *
 * @param name        preset name
 * @param description one-liner shown by {@code jarness presets}
 * @param mods        id allow-list; empty means all mods
 * @param instruments instrument {@code modId:instrumentName} to explicit enable/disable decisions
 * @param template    template an agent should default to under this preset, or {@code null}
 * @param provider    provider profile this preset prefers, or {@code null}
 * @param model       model this preset prefers, or {@code null}
 * @param source      the file this preset came from, or {@code null} for a built-in
 * @param builtin     whether this preset ships with the runtime
 */
public record Preset(
        String name,
        String description,
        Set<String> mods,
        Map<String, Boolean> instruments,
        String template,
        String provider,
        String model,
        Path source,
        boolean builtin) {

    public Preset {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        mods = mods == null ? Set.of() : Set.copyOf(mods);
        instruments = instruments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(instruments));
    }

    /** @return an allow-everything preset, used when the user names no preset. */
    public static Preset all(String name) {
        return new Preset(name, "load every mod with author defaults", Set.of(), Map.of(),
                null, null, null, null, false);
    }

    /** @return whether the mod allow-list admits {@code modId}. */
    public boolean admitsMod(String modId) {
        return mods.isEmpty() || mods.contains(modId);
    }

    /**
     * Decide whether one instrument should be enabled.
     *
     * @param modId        owning mod id
     * @param instrument   instrument name within the mod
     * @param autoEnable   the author's declared policy
     * @return whether the instrument is enabled under this preset
     */
    public boolean enablesInstrument(String modId, String instrument, boolean autoEnable) {
        Boolean explicit = instruments.get(modId + ":" + instrument);
        if (explicit == null) {
            explicit = instruments.get(instrument);
        }
        return explicit == null ? autoEnable : explicit;
    }
}
