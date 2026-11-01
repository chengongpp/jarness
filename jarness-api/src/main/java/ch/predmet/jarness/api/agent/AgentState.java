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
    /** Checkpointed and detached from the process; the image can be resumed later. */
    SUSPENDED,
    /** Stop requested; the worker is draining an in-flight turn. */
    DEACTIVATING,
    /** Being recreated by the restart policy. */
    RESTARTING,
    /** Stopped normally; the agent will not accept more work. */
    STOPPED,
    /** Stopped because a turn failed unrecoverably. */
    FAILED
}
