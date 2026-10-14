package ch.predmet.jarness.api.agent;

/** Observable lifecycle state of one agent. */
public enum AgentState {
    /** Created but not yet started. */
    NEW,
    /** Alive and waiting for work. */
    IDLE,
    /** Executing a turn. */
    RUNNING,
    /** Paused by a signal, or waiting on delegated work. */
    WAITING,
    /** Stopped normally; the agent will not accept more work. */
    STOPPED,
    /** Stopped because a turn failed unrecoverably. */
    FAILED
}
