package ch.predmet.jarness.api.mod;

import ch.predmet.jarness.api.instrument.Instrument;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of one load pass (initial load or a dynamic {@code loadJar}).
 *
 * @param loaded     mods that finished loading, in resolved load order
 * @param errors     problems found; any entry means the pass failed
 * @param warnings   non-fatal remarks worth showing
 * @param applied    instruments that were applied during this pass
 * @param duration   wall time of the pass
 */
public record LoadReport(
        List<LoadedMod> loaded,
        List<ModLoadError> errors,
        List<String> warnings,
        List<Instrument> applied,
        Duration duration) {

    public LoadReport {
        loaded = loaded == null ? List.of() : List.copyOf(loaded);
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        applied = applied == null ? List.of() : List.copyOf(applied);
        duration = duration == null ? Duration.ZERO : duration;
    }

    /** @return an empty report used before any load happened. */
    public static LoadReport empty() {
        return new LoadReport(List.of(), List.of(), List.of(), List.of(), Duration.ZERO);
    }

    /** @return whether the pass produced no errors. */
    public boolean ok() {
        return errors.isEmpty();
    }

    /** @return the ids of the loaded mods in load order. */
    public List<String> loadedIds() {
        return loaded.stream().map(m -> m.descriptor().id()).toList();
    }

    /**
     * Render every error and warning for console output.
     *
     * @return a printable block, or an empty string when the pass was clean
     */
    public String render() {
        if (ok() && warnings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ModLoadError error : errors) {
            sb.append("ERROR ").append(error.render()).append('\n');
        }
        for (String warning : warnings) {
            sb.append("WARN  ").append(warning).append('\n');
        }
        return sb.toString();
    }
}
