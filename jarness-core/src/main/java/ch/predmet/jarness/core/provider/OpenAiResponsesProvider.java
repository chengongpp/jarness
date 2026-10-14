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
 * OpenAI {@code /responses} adapter.
 *
 * <p>Requests are non-streaming: the complete response is parsed first and then replayed into the
 * sink ({@code onStart}, {@code onReasoning}/{@code onText} with the full text, {@code onUsage},
 * {@code onFinish}). Image blocks and prior reasoning blocks are not sent.</p>
 */
public final class OpenAiResponsesProvider extends AbstractHttpProvider {

    /**
     * @param profile the configured profile
     */
    public OpenAiResponsesProvider(ProviderProfile profile) {
        super(profile, ProviderKind.OPENAI_RESPONSES, HttpTransport.AuthStyle.BEARER);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        return complete(request, StreamSink.DISCARD);
    }

    @Override
    public ChatResponse complete(ChatRequest request, StreamSink sink) {
        ObjectNode body = buildRequest(request);
        JsonNode root = http.postJson(http.baseUrl() + "/responses", body, Map.of());
        String raw = root.toString();

        String id = orEmpty(HttpTransport.textOf(root.get("id")));
        sink.onStart(id);

        List<ContentBlock> blocks = new ArrayList<>();
        StringBuilder reasoning = new StringBuilder();
        StringBuilder text = new StringBuilder();
        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                String type = HttpTransport.textOf(item.get("type"));
                if (type == null) {
                    continue;
                }
                switch (type) {
                    case "message" -> {
                        JsonNode content = item.get("content");
                        if (content != null && content.isArray()) {
                            for (JsonNode part : content) {
                                if ("output_text".equals(HttpTransport.textOf(part.get("type")))) {
                                    String partText = HttpTransport.textOf(part.get("text"));
                                    if (partText != null) {
                                        text.append(partText);
                                    }
                                }
                            }
                        }
                    }
                    case "function_call" -> {
                        String arguments = orEmpty(HttpTransport.textOf(item.get("arguments")));
                        blocks.add(new ContentBlock.ToolCall(
                                orEmpty(HttpTransport.textOf(item.get("call_id"))),
                                orEmpty(HttpTransport.textOf(item.get("name"))),
                                arguments));
                    }
                    case "reasoning" -> {
                        JsonNode summary = item.get("summary");
                        if (summary != null && summary.isArray()) {
                            for (JsonNode part : summary) {
                                String partText = HttpTransport.textOf(part.get("text"));
                                if (partText != null) {
                                    reasoning.append(partText);
                                }
                            }
                        }
                    }
                    default -> {
                        // unhandled output item kinds are ignored
                    }
                }
            }
        }

        List<ContentBlock> ordered = new ArrayList<>();
        if (!reasoning.isEmpty()) {
            ordered.add(new ContentBlock.Reasoning(reasoning.toString()));
            sink.onReasoning(reasoning.toString());
        }
        if (!text.isEmpty()) {
            ordered.add(new ContentBlock.Text(text.toString()));
            sink.onText(text.toString());
        }
        ordered.addAll(blocks);

        Usage usage = Usage.EMPTY;
        JsonNode usageNode = root.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            int cached = usageNode.path("input_tokens_details").path("cached_tokens").asInt(0);
            usage = new Usage(
                    usageNode.path("input_tokens").asInt(0),
                    usageNode.path("output_tokens").asInt(0),
                    cached);
            sink.onUsage(usage);
        }
        String finishReason = orEmpty(HttpTransport.textOf(root.get("status")));
        sink.onFinish(finishReason);

        return new ChatResponse(id, new Message(null, Role.ASSISTANT, ordered, null), usage, finishReason, raw);
    }

    private ObjectNode buildRequest(ChatRequest request) {
        ObjectNode body = HttpTransport.MAPPER.createObjectNode();
        body.put("model", request.model().modelId());

        StringBuilder instructions = new StringBuilder(request.system());
        ArrayNode input = body.putArray("input");
        for (Message message : request.messages()) {
            switch (message.role()) {
                case SYSTEM -> {
                    String text = Schemas.textOf(message);
                    if (!text.isBlank()) {
                        if (!instructions.isEmpty()) {
                            instructions.append('\n');
                        }
                        instructions.append(text);
                    }
                }
                case USER -> appendUser(input, message);
                case ASSISTANT -> appendAssistant(input, message);
                case TOOL -> appendToolResults(input, message);
            }
        }
        if (!instructions.isEmpty()) {
            body.put("instructions", instructions.toString());
        }
        if (request.hasTools()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                tool.put("name", spec.name());
                tool.put("description", spec.description());
                tool.set("parameters", Schemas.parametersSchema(spec));
            }
        }
        if (request.maxTokens() > 0) {
            body.put("max_output_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        request.extra().forEach((key, value) ->
                body.set(key, HttpTransport.MAPPER.valueToTree(value)));
        return body;
    }

    private void appendUser(ArrayNode input, Message message) {
        appendToolResults(input, message);
        String text = Schemas.textOf(message);
        if (!text.isEmpty()) {
            // image blocks are skipped: this adapter is text-only
            input.addObject().put("role", "user").put("content", text);
        }
    }

    private void appendAssistant(ArrayNode input, Message message) {
        String text = Schemas.textOf(message);
        if (!text.isEmpty()) {
            input.addObject().put("role", "assistant").put("content", text);
        }
        // reasoning blocks are not sent back to the endpoint
        for (ContentBlock.ToolCall call : message.toolCalls()) {
            ObjectNode item = input.addObject();
            item.put("type", "function_call");
            item.put("call_id", call.callId());
            item.put("name", call.name());
            item.put("arguments", call.arguments());
        }
    }

    private void appendToolResults(ArrayNode input, Message message) {
        for (ContentBlock.ToolResult result : message.toolResults()) {
            ObjectNode item = input.addObject();
            item.put("type", "function_call_output");
            item.put("call_id", result.callId());
            item.put("output", result.text());
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
