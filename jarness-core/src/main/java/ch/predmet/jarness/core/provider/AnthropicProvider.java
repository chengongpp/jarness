package ch.predmet.jarness.core.provider;

import ch.predmet.jarness.api.provider.ChatRequest;
import ch.predmet.jarness.api.provider.ChatResponse;
import ch.predmet.jarness.api.provider.ProviderException;
import ch.predmet.jarness.api.provider.ProviderKind;
import ch.predmet.jarness.api.provider.ProviderProfile;
import ch.predmet.jarness.api.provider.StreamSink;
import ch.predmet.jarness.api.provider.Usage;
import ch.predmet.jarness.api.session.ContentBlock;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Role;
import ch.predmet.jarness.api.tool.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic {@code /v1/messages} adapter.
 *
 * <p>Requests are non-streaming: the complete response is parsed first and then replayed into the
 * sink ({@code onStart}, {@code onText} with the full text, {@code onUsage}, {@code onFinish}).
 * Tool results are sent as {@code tool_result} blocks inside a user message, and consecutive
 * same-role messages are merged so the wire format always alternates user/assistant. Image blocks
 * and prior reasoning blocks are not sent.</p>
 */
public final class AnthropicProvider extends AbstractHttpProvider {

    private static final Map<String, String> VERSION_HEADER = Map.of("anthropic-version", "2023-06-01");

    /**
     * @param profile the configured profile
     */
    public AnthropicProvider(ProviderProfile profile) {
        super(profile, ProviderKind.ANTHROPIC, HttpTransport.AuthStyle.X_API_KEY);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        return complete(request, StreamSink.DISCARD);
    }

    @Override
    public ChatResponse complete(ChatRequest request, StreamSink sink) {
        ObjectNode body = buildRequest(request);
        JsonNode root = http.postJson(messagesUrl(), body, VERSION_HEADER, request.deadline());
        String raw = root.toString();

        String id = orEmpty(HttpTransport.textOf(root.get("id")));
        sink.onStart(id);

        List<ContentBlock> blocks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        JsonNode content = root.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode part : content) {
                String type = HttpTransport.textOf(part.get("type"));
                if ("text".equals(type)) {
                    String partText = HttpTransport.textOf(part.get("text"));
                    if (partText != null) {
                        text.append(partText);
                    }
                } else if ("tool_use".equals(type)) {
                    JsonNode input = part.get("input");
                    String arguments;
                    try {
                        arguments = input == null || input.isNull()
                                ? "{}"
                                : HttpTransport.MAPPER.writeValueAsString(input);
                    } catch (IOException e) {
                        throw new ProviderException("cannot serialize tool_use input from profile \"" + id() + "\"", e);
                    }
                    blocks.add(new ContentBlock.ToolCall(
                            orEmpty(HttpTransport.textOf(part.get("id"))),
                            orEmpty(HttpTransport.textOf(part.get("name"))),
                            arguments));
                }
            }
        }

        List<ContentBlock> ordered = new ArrayList<>();
        if (!text.isEmpty()) {
            ordered.add(new ContentBlock.Text(text.toString()));
            sink.onText(text.toString());
        }
        ordered.addAll(blocks);

        Usage usage = Usage.EMPTY;
        JsonNode usageNode = root.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            usage = new Usage(
                    usageNode.path("input_tokens").asInt(0),
                    usageNode.path("output_tokens").asInt(0),
                    usageNode.path("cache_read_input_tokens").asInt(0));
            sink.onUsage(usage);
        }
        String finishReason = orEmpty(HttpTransport.textOf(root.get("stop_reason")));
        sink.onFinish(finishReason);

        return new ChatResponse(id, new Message(null, Role.ASSISTANT, ordered, null), usage, finishReason, raw);
    }

    private String messagesUrl() {
        String base = http.baseUrl();
        return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
    }

    private ObjectNode buildRequest(ChatRequest request) {
        ObjectNode body = HttpTransport.MAPPER.createObjectNode();
        body.put("model", request.model().modelId());
        body.put("max_tokens", Math.max(request.maxTokens(), 1));

        StringBuilder system = new StringBuilder(request.system());
        // Segments keep the wire format alternating: consecutive same-role content is merged.
        List<Segment> segments = new ArrayList<>();
        for (Message message : request.messages()) {
            switch (message.role()) {
                case SYSTEM -> {
                    String text = Schemas.textOf(message);
                    if (!text.isBlank()) {
                        if (!system.isEmpty()) {
                            system.append('\n');
                        }
                        system.append(text);
                    }
                }
                case USER -> appendUserMessage(segments, message);
                case ASSISTANT -> appendAssistantMessage(segments, message);
                case TOOL -> appendToolResults(segments, message);
            }
        }
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }
        ArrayNode messages = body.putArray("messages");
        for (Segment segment : segments) {
            ObjectNode node = messages.addObject();
            node.put("role", segment.role);
            node.set("content", segment.content);
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (!request.stop().isEmpty()) {
            ArrayNode stop = body.putArray("stop_sequences");
            request.stop().forEach(stop::add);
        }
        if (request.hasTools()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("name", spec.name());
                tool.put("description", spec.description());
                tool.set("input_schema", Schemas.parametersSchema(spec));
            }
        }
        request.extra().forEach((key, value) ->
                body.set(key, HttpTransport.MAPPER.valueToTree(value)));
        return body;
    }

    private void appendUserMessage(List<Segment> segments, Message message) {
        for (ContentBlock block : message.content()) {
            if (block instanceof ContentBlock.ToolResult result) {
                addBlock(segments, "user", toolResultBlock(result));
            } else if (block instanceof ContentBlock.Text text) {
                // image blocks are skipped: this adapter is text-only
                if (!text.text().isEmpty()) {
                    ObjectNode node = HttpTransport.MAPPER.createObjectNode();
                    node.put("type", "text");
                    node.put("text", text.text());
                    addBlock(segments, "user", node);
                }
            }
        }
    }

    private void appendAssistantMessage(List<Segment> segments, Message message) {
        for (ContentBlock block : message.content()) {
            if (block instanceof ContentBlock.Text text) {
                if (!text.text().isEmpty()) {
                    ObjectNode node = HttpTransport.MAPPER.createObjectNode();
                    node.put("type", "text");
                    node.put("text", text.text());
                    addBlock(segments, "assistant", node);
                }
            } else if (block instanceof ContentBlock.ToolCall call) {
                // reasoning blocks are not sent back to the endpoint
                ObjectNode node = HttpTransport.MAPPER.createObjectNode();
                node.put("type", "tool_use");
                node.put("id", call.callId());
                node.put("name", call.name());
                JsonNode input;
                try {
                    input = call.arguments() == null || call.arguments().isBlank()
                            ? HttpTransport.MAPPER.createObjectNode()
                            : HttpTransport.MAPPER.readTree(call.arguments());
                } catch (IOException e) {
                    throw new ProviderException("tool call \"" + call.name()
                            + "\" carries unparsable arguments", e);
                }
                node.set("input", input);
                addBlock(segments, "assistant", node);
            } else if (block instanceof ContentBlock.ToolResult result) {
                addBlock(segments, "user", toolResultBlock(result));
            }
        }
    }

    private void appendToolResults(List<Segment> segments, Message message) {
        for (ContentBlock.ToolResult result : message.toolResults()) {
            addBlock(segments, "user", toolResultBlock(result));
        }
    }

    private ObjectNode toolResultBlock(ContentBlock.ToolResult result) {
        ObjectNode node = HttpTransport.MAPPER.createObjectNode();
        node.put("type", "tool_result");
        node.put("tool_use_id", result.callId());
        node.put("content", result.text());
        node.put("is_error", !result.ok());
        return node;
    }

    private static void addBlock(List<Segment> segments, String role, ObjectNode block) {
        if (!segments.isEmpty() && segments.get(segments.size() - 1).role.equals(role)) {
            segments.get(segments.size() - 1).content.add(block);
        } else {
            Segment segment = new Segment(role);
            segment.content.add(block);
            segments.add(segment);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** One wire message under construction: a role plus its accumulated content blocks. */
    private static final class Segment {
        final String role;
        final ArrayNode content = HttpTransport.MAPPER.createArrayNode();

        Segment(String role) {
            this.role = role;
        }
    }
}
