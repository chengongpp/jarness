package ch.predmet.jarness.api.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One immutable conversation message.
 *
 * @param id      stable message id
 * @param role    conversation role
 * @param content ordered content blocks
 * @param at      creation time
 */
public record Message(String id, Role role, List<ContentBlock> content, Instant at) {

    public Message {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        content = content == null ? List.of() : List.copyOf(content);
        at = at == null ? Instant.now() : at;
    }

    /** @return a user message carrying {@code text}. */
    public static Message user(String text) {
        return new Message(null, Role.USER, List.of(new ContentBlock.Text(text)), Instant.now());
    }

    /** @return a system message carrying {@code text}. */
    public static Message system(String text) {
        return new Message(null, Role.SYSTEM, List.of(new ContentBlock.Text(text)), Instant.now());
    }

    /** @return a tool observation message. */
    public static Message toolResult(String callId, String name, boolean ok, String text) {
        return new Message(null, Role.TOOL, List.of(new ContentBlock.ToolResult(callId, name, ok, text)), Instant.now());
    }

    /** @return a message with these blocks and {@code role}. */
    public static Message of(Role role, ContentBlock... blocks) {
        return new Message(null, role, List.of(blocks), Instant.now());
    }

    /** @return the concatenated text blocks of this message. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.Text text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    /** @return the concatenated reasoning blocks of this message. */
    public String reasoning() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.Reasoning reasoning) {
                sb.append(reasoning.text());
            }
        }
        return sb.toString();
    }

    /** @return the tool calls requested by this message, in order. */
    public List<ContentBlock.ToolCall> toolCalls() {
        List<ContentBlock.ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.ToolCall call) {
                calls.add(call);
            }
        }
        return calls;
    }

    /** @return the tool observations in this message, in order. */
    public List<ContentBlock.ToolResult> toolResults() {
        List<ContentBlock.ToolResult> results = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.ToolResult result) {
                results.add(result);
            }
        }
        return results;
    }

    /** @return whether this message contains at least one tool call. */
    public boolean hasToolCalls() {
        return content.stream().anyMatch(ContentBlock.ToolCall.class::isInstance);
    }

    /** @return the same message with an explicit id. */
    public Message withId(String newId) {
        return new Message(newId, role, content, at);
    }
}
