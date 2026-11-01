package ch.predmet.jarness.api.tool;

/** How a tool is implemented. */
public enum ToolForm {

    /** The handler runs inside the daemon JVM. */
    LOCAL,
    /** The handler is an executor tool; the daemon relays the call. */
    RELAY
}
