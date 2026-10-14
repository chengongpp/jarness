package ch.predmet.jarness.api.mod;

import java.nio.file.Path;

/**
 * One recoverable or fatal problem met while loading mods.
 *
 * <p>NeoForge-style diagnostics: the runtime would rather print a precise, actionable reason than
 * a bare stack trace, so every error carries the phase it happened in plus an optional hint.</p>
 *
 * @param modId   the mod being processed, or {@code "<runtime>"} for global failures
 * @param file    the file involved, if any
 * @param phase   coarse phase: DISCOVERY, PARSE, RESOLVE, INSTANTIATE, REGISTER, PRESET
 * @param message what went wrong
 * @param detail  exception type and message, if any
 * @param hint    what the user can do about it
 */
public record ModLoadError(String modId, Path file, String phase, String message, String detail, String hint) {

    public ModLoadError {
        modId = modId == null ? "<runtime>" : modId;
        phase = phase == null ? "UNKNOWN" : phase;
        detail = detail == null ? "" : detail;
        hint = hint == null ? "" : hint;
    }

    /** @return a single-line summary used in console output. */
    public String headline() {
        return "[" + phase + "] " + modId + ": " + message;
    }

    /** @return a multi-line, human-facing rendering including file, detail, and hint. */
    public String render() {
        StringBuilder sb = new StringBuilder(headline());
        if (file != null) {
            sb.append("\n    file    : ").append(file);
        }
        if (!detail.isBlank()) {
            sb.append("\n    detail  : ").append(detail);
        }
        if (!hint.isBlank()) {
            sb.append("\n    hint    : ").append(hint);
        }
        return sb.toString();
    }
}
