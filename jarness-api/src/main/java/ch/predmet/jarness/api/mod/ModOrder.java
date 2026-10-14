package ch.predmet.jarness.api.mod;

/**
 * Relative load ordering requested against another mod, mirroring FancyModLoader's
 * ordering hints. {@link #NONE} declares no ordering constraint beyond dependency presence.
 */
public enum ModOrder {
    NONE,
    BEFORE,
    AFTER
}
