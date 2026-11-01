package ch.predmet.jarness.api.hook;

/**
 * Lifecycle seams a hook may attach to.
 *
 * <p>Points that carry a mutable payload let a hook rewrite what happens next (rewrite the outgoing
 * request, replace a tool result, cancel the turn); points with an immutable payload are
 * observational. Which points exist is deliberately close to the set deepseek-harness exposes, so
 * behavior known from that runtime transfers.</p>
 */
public enum HookPoint {

    /** The runtime finished construction and is about to serve. */
    RUNTIME_START,
    /** A load pass finished; payload is the {@code LoadReport}. */
    MODS_LOADED,
    /** One mod finished registering; payload is a {@code ModEvent}. */
    MOD_LOADED,
    /** One instrument finished applying; payload is a {@code ModEvent} carrying the instrument. */
    INSTRUMENT_APPLIED,
    /** The supervisor is shutting down. */
    RUNTIME_STOP,

    /** A new agent object exists, before it starts working; payload is an {@code AgentEvent}. */
    AGENT_CREATED,
    /** An agent is about to accept work; payload is an {@code AgentEvent}. */
    AGENT_STARTING,
    /** An agent stopped; payload is an {@code AgentEvent} whose reason explains why. */
    AGENT_STOPPED,
    /** A failed agent was restarted by policy; payload is an {@code AgentEvent} with the new pid. */
    AGENT_RESTARTED,
    /** A signal was delivered to an agent; payload is an {@code AgentEvent}. */
    AGENT_SIGNAL,
    /** A spawn or fork was refused; payload is a {@code SpawnEvent}. */
    SPAWN_DENIED,

    /** A session was created or resumed; payload is a {@code SessionEvent}. */
    SESSION_CREATED,
    /** A session is closing; payload is a {@code SessionEvent}. */
    SESSION_ENDED,

    /** A turn begins; payload is a {@code TurnEvent}. */
    TURN_START,
    /** A provider/tool step begins; payload is a {@code TurnEvent} carrying the step index. */
    STEP_START,
    /** Outgoing completion is fully assembled and mutable; payload is a {@code RequestEvent}. */
    BEFORE_REQUEST,
    /** A completion returned; payload is a {@code ResponseEvent}. */
    AFTER_RESPONSE,
    /** A tool call is about to execute and may be rewritten or skipped; payload is a {@code ToolCallEvent}. */
    BEFORE_TOOL_CALL,
    /** A tool call finished and its observation may be rewritten; payload is a {@code ToolCallEvent}. */
    AFTER_TOOL_CALL,
    /** A message is about to be appended to a session; payload is a {@code MessageEvent}. */
    MESSAGE_APPENDED,
    /** A turn ended, successfully or not; payload is a {@code TurnEvent}. */
    TURN_END
}
