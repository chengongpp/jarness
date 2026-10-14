package ch.predmet.jarness.api.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The observation a tool returns to the model.
 *
 * @param ok         whether the call succeeded
 * @param text       model-visible observation text
 * @param structured optional structured payload for hosts and mods; never sent to the model verbatim
 * @param error      failure summary when {@code ok} is false
 */
public record ToolOutcome(boolean ok, String text, JsonNode structured, String error) {

    public ToolOutcome {
        text = text == null ? "" : text;
        error = error == null ? "" : error;
    }

    /** @return a successful text observation. */
    public static ToolOutcome ok(String text) {
        return new ToolOutcome(true, text, null, "");
    }

    /** @return a successful observation carrying structured data. */
    public static ToolOutcome ok(String text, JsonNode structured) {
        return new ToolOutcome(true, text, structured, "");
    }

    /** @return a failed observation the model should read. */
    public static ToolOutcome error(String message) {
        return new ToolOutcome(false, "", null, message == null ? "tool failed" : message);
    }

    /** @return a failed observation with both a summary and a model-facing text. */
    public static ToolOutcome error(String message, String text) {
        return new ToolOutcome(false, text == null ? "" : text, null, message);
    }

    /** @return a refusal produced by a hook, sandbox rule, or the approval policy. */
    public static ToolOutcome denied(String reason) {
        return new ToolOutcome(false, "", null, "denied: " + reason);
    }

    /** @return the text the model sees, falling back to the error when there is no text. */
    public String modelText() {
        return text.isEmpty() && !error.isEmpty() ? error : text;
    }
}
