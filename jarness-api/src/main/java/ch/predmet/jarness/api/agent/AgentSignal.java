package ch.predmet.jarness.api.agent;

/** A control signal the supervisor or a tool can deliver to a running agent. */
public enum AgentSignal {

    /** Finish the current step, then hold before the next one. */
    PAUSE,
    /** Resume after a pause. */
    RESUME,
    /** Abandon the current turn immediately and go idle. */
    INTERRUPT,
    /** Shut the agent down after the current step. */
    STOP
}
