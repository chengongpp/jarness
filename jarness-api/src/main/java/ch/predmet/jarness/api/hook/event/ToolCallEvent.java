package ch.predmet.jarness.api.hook.event;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;

/**
 * Mutable payload for {@code BEFORE_TOOL_CALL} and {@code AFTER_TOOL_CALL}.
 *
 * <p>Before execution a hook may {@link #skip(String)} the call (producing a refusal the model
 * reads) or rewrite the invocation; after execution it may replace the {@link #outcome()} before the
 * observation reaches the session. This is how a policy mod implements approvals, path guards, or
 * output redaction without forking the loop.</p>
 */
public final class ToolCallEvent {

    private final Agent agent;
    private final Session session;
    private final Turn turn;
    private final Tool tool;
    private ToolInvocation invocation;
    private ToolOutcome outcome;
    private boolean skipped;
    private String skipReason = "";

    /**
     * @param agent      the calling agent
     * @param session    its session
     * @param turn       the running turn
     * @param tool       the resolved tool, or {@code null} when the model named an unknown tool
     * @param invocation the call
     */
    public ToolCallEvent(Agent agent, Session session, Turn turn, Tool tool, ToolInvocation invocation) {
        this.agent = agent;
        this.session = session;
        this.turn = turn;
        this.tool = tool;
        this.invocation = invocation;
    }

    /** @return the calling agent. */
    public Agent agent() {
        return agent;
    }

    /** @return the session. */
    public Session session() {
        return session;
    }

    /** @return the running turn. */
    public Turn turn() {
        return turn;
    }

    /** @return the resolved tool, or {@code null} when the name was unknown. */
    public Tool tool() {
        return tool;
    }

    /** @return the invocation, possibly rewritten by an earlier hook. */
    public ToolInvocation invocation() {
        return invocation;
    }

    /** Replace the invocation arguments. */
    public void setInvocation(ToolInvocation value) {
        if (value != null) {
            this.invocation = value;
        }
    }

    /** @return the outcome, or {@code null} before execution. */
    public ToolOutcome outcome() {
        return outcome;
    }

    /** Replace the outcome. */
    public void setOutcome(ToolOutcome value) {
        this.outcome = value;
    }

    /** Refuse the call: the tool does not run and the model receives {@code reason}. */
    public void skip(String reason) {
        if (!skipped) {
            this.skipped = true;
            this.skipReason = reason == null ? "skipped by hook" : reason;
        }
    }

    /** @return whether a hook refused the call. */
    public boolean skipped() {
        return skipped;
    }

    /** @return why the call was refused. */
    public String skipReason() {
        return skipReason;
    }

    /** @return whether the call produced a successful outcome. */
    public boolean ok() {
        return outcome != null && outcome.ok();
    }
}
