package ch.predmet.jarness.api.tool;

/** Whether a tool call blocks the turn or is accepted and completed later. */
public enum ToolMode {

    /** The turn waits for the result. */
    SYNC,
    /** The call is accepted immediately; the result arrives as a later event. */
    ASYNC
}
