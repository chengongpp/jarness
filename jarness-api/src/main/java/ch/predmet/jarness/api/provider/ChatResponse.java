package ch.predmet.jarness.api.provider;

import ch.predmet.jarness.api.session.Message;

/**
 * One completion response, already normalized into Jarness message blocks.
 *
 * @param id           provider response id
 * @param message      the assistant message, including reasoning and tool-call blocks
 * @param usage        token accounting
 * @param finishReason provider finish reason as reported
 * @param raw          the provider's raw response body, kept for diagnostics and mods
 */
public record ChatResponse(String id, Message message, Usage usage, String finishReason, String raw) {

    public ChatResponse {
        usage = usage == null ? Usage.EMPTY : usage;
        finishReason = finishReason == null ? "" : finishReason;
        raw = raw == null ? "" : raw;
    }

    /** @return the assistant text. */
    public String text() {
        return message == null ? "" : message.text();
    }

    /** @return whether the model asked for at least one tool call. */
    public boolean hasToolCalls() {
        return message != null && message.hasToolCalls();
    }
}
