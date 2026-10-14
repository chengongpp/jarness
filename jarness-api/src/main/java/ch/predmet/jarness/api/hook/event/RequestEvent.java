package ch.predmet.jarness.api.hook.event;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.List;

/**
 * Mutable payload for {@code BEFORE_REQUEST}: everything about the outgoing completion that a hook
 * may rewrite, plus a cancellation switch.
 *
 * <p>This is the main lever a context-management mod uses: it can drop or reorder history, swap the
 * system prompt, restrict the advertised tools, or refuse the request outright. Jarness does not
 * pretend this is a pure function; hooks mutate the pending request in place.</p>
 */
public final class RequestEvent {

    private final Agent agent;
    private final Session session;
    private final Turn turn;
    private final int step;
    private String system;
    private List<Message> messages;
    private List<ToolSpec> tools;
    private ModelRef model;
    private int maxTokens;
    private Double temperature;
    private boolean cancelled;
    private String cancelReason = "";

    /**
     * @param agent     the requesting agent
     * @param session   its session
     * @param turn      the running turn
     * @param step      step index inside the turn
     * @param system    the rendered system prompt
     * @param messages  the conversation to send
     * @param tools     the advertised tools
     * @param model     the target model
     * @param maxTokens completion budget
     * @param temperature sampling temperature, or {@code null}
     */
    public RequestEvent(Agent agent, Session session, Turn turn, int step, String system,
                        List<Message> messages, List<ToolSpec> tools, ModelRef model,
                        int maxTokens, Double temperature) {
        this.agent = agent;
        this.session = session;
        this.turn = turn;
        this.step = step;
        this.system = system;
        this.messages = messages;
        this.tools = tools;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    /** @return the requesting agent. */
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

    /** @return the step index. */
    public int step() {
        return step;
    }

    /** @return the system prompt about to be sent. */
    public String system() {
        return system;
    }

    /** Replace the system prompt. */
    public void setSystem(String value) {
        this.system = value == null ? "" : value;
    }

    /** @return the conversation about to be sent. */
    public List<Message> messages() {
        return messages;
    }

    /** Replace the conversation. */
    public void setMessages(List<Message> value) {
        this.messages = value == null ? List.of() : List.copyOf(value);
    }

    /** @return the advertised tools. */
    public List<ToolSpec> tools() {
        return tools;
    }

    /** Replace the advertised tools. */
    public void setTools(List<ToolSpec> value) {
        this.tools = value == null ? List.of() : List.copyOf(value);
    }

    /** @return the target model. */
    public ModelRef model() {
        return model;
    }

    /** Retarget the request. */
    public void setModel(ModelRef value) {
        this.model = value;
    }

    /** @return the completion budget. */
    public int maxTokens() {
        return maxTokens;
    }

    /** Replace the completion budget. */
    public void setMaxTokens(int value) {
        this.maxTokens = value;
    }

    /** @return the sampling temperature, or {@code null}. */
    public Double temperature() {
        return temperature;
    }

    /** Replace the sampling temperature. */
    public void setTemperature(Double value) {
        this.temperature = value;
    }

    /** Refuse the request; the loop turns this into a failed step. */
    public void cancel(String reason) {
        if (!cancelled) {
            this.cancelled = true;
            this.cancelReason = reason == null ? "cancelled by hook" : reason;
        }
    }

    /** @return whether a hook cancelled the request. */
    public boolean cancelled() {
        return cancelled;
    }

    /** @return the cancellation reason. */
    public String cancelReason() {
        return cancelReason;
    }
}
