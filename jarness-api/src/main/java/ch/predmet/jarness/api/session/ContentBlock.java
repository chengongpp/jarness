package ch.predmet.jarness.api.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One piece of message content.
 *
 * <p>Blocks are the storage format for both the session store and provider adapters, so reasoning
 * text, tool calls, and tool observations survive a resume instead of being flattened into strings.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.Text.class, name = "text"),
        @JsonSubTypes.Type(value = ContentBlock.Reasoning.class, name = "reasoning"),
        @JsonSubTypes.Type(value = ContentBlock.ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = ContentBlock.Image.class, name = "image")
})
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.Reasoning, ContentBlock.ToolCall, ContentBlock.ToolResult, ContentBlock.Image {

    /**
     * Model-visible text.
     *
     * @param text the text
     */
    record Text(String text) implements ContentBlock {
    }

    /**
     * Model reasoning content, retained for display and for providers that replay it.
     *
     * @param text the reasoning text
     */
    record Reasoning(String text) implements ContentBlock {
    }

    /**
     * A tool call requested by the model.
     *
     * @param callId    provider-assigned call id
     * @param name      tool name
     * @param arguments raw JSON arguments exactly as the model produced them
     */
    record ToolCall(String callId, String name, String arguments) implements ContentBlock {
    }

    /**
     * The observation a tool produced.
     *
     * @param callId the call this observes
     * @param name   tool name
     * @param ok     whether the tool succeeded
     * @param text   model-visible observation text
     */
    record ToolResult(String callId, String name, boolean ok, String text) implements ContentBlock {
    }

    /**
     * An image attachment.
     *
     * @param mimeType image media type
     * @param data     raw bytes
     */
    record Image(String mimeType, byte[] data) implements ContentBlock {
    }
}
