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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OpenAI-compatible {@code /chat/completions} adapter.
 *
 * <p>Every request is sent with {@code stream: true} and {@code stream_options.include_usage};
 * {@link #complete(ChatRequest)} simply delegates to {@link #complete(ChatRequest, StreamSink)}
 * with {@link StreamSink#DISCARD}. The SSE stream is parsed chunk by chunk: text deltas, reasoning
 * deltas ({@code reasoning} with a {@code reasoning_content} fallback), tool calls keyed by
 * {@code index} (first chunk carries id/name, later chunks argument fragments), the finish reason
 * and the trailing usage. Endpoints that ignore {@code stream} and answer with one plain JSON
 * body are still accepted. Image blocks are not sent (this adapter is text-only), and reasoning
 * blocks of prior assistant messages are not replayed.</p>
 */
public final class OpenAiCompatProvider extends AbstractHttpProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatProvider.class);

    /**
     * @param profile the configured profile
     */
    public OpenAiCompatProvider(ProviderProfile profile) {
        super(profile, ProviderKind.OPENAI_COMPAT, HttpTransport.AuthStyle.BEARER);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        return complete(request, StreamSink.DISCARD);
    }

    @Override
    public ChatResponse complete(ChatRequest request, StreamSink sink) {
        ObjectNode body = buildRequest(request);
        HttpResponse<InputStream> response =
                http.postStream(http.baseUrl() + "/chat/completions", body, Map.of(), request.deadline());
        String contentType = HttpTransport.contentType(response);

        String id = "";
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        TreeMap<Integer, ToolCallAcc> toolCalls = new TreeMap<>();
        String finishReason = "";
        Usage usage = Usage.EMPTY;
        boolean sawData = false;
        boolean started = false;
        StringBuilder nonSseBody = new StringBuilder();

        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.cancelled()) {
                    // try-with-resources closes the body, aborting the stream
                    throw new ProviderException("cancelled");
                }
                if (line.isBlank()) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    nonSseBody.append(line).append('\n');
                    continue;
                }
                sawData = true;
                String payload = line.substring("data:".length()).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = HttpTransport.MAPPER.readTree(payload);
                } catch (IOException e) {
                    throw ProviderException.parse("profile " + id() + " returned an unparsable SSE chunk", e);
                }
                if (!started) {
                    id = orEmpty(HttpTransport.textOf(chunk.get("id")));
                    sink.onStart(id);
                    started = true;
                }
                JsonNode choice = chunk.path("choices").path(0);
                if (!choice.isMissingNode()) {
                    JsonNode delta = choice.get("delta");
                    if (delta != null && delta.isObject()) {
                        String content = HttpTransport.textOf(delta.get("content"));
                        if (content != null && !content.isEmpty()) {
                            text.append(content);
                            sink.onText(content);
                        }
                        String reasoningDelta = HttpTransport.textOf(delta.get("reasoning"));
                        if (reasoningDelta == null) {
                            reasoningDelta = HttpTransport.textOf(delta.get("reasoning_content"));
                        }
                        if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                            reasoning.append(reasoningDelta);
                            sink.onReasoning(reasoningDelta);
                        }
                        JsonNode calls = delta.get("tool_calls");
                        if (calls != null && calls.isArray()) {
                            for (JsonNode call : calls) {
                                accumulateToolCall(call, toolCalls, sink);
                            }
                        }
                    }
                    String finish = HttpTransport.textOf(choice.get("finish_reason"));
                    if (finish != null) {
                        finishReason = finish;
                    }
                }
                JsonNode usageNode = chunk.get("usage");
                if (usageNode != null && usageNode.isObject()) {
                    usage = parseUsage(usageNode);
                    sink.onUsage(usage);
                }
            }
        } catch (IOException e) {
            throw ProviderException.transport("stream from profile \"" + id() + "\" failed: "
                    + e.getMessage(), e);
        }

        if (!sawData && contentType.contains("json") && !nonSseBody.isEmpty()) {
            // endpoint ignored stream:true and answered with one plain JSON response
            return parsePlainResponse(nonSseBody.toString(), sink);
        }

        sink.onFinish(finishReason);
        return new ChatResponse(id, assembleMessage(reasoning, text, toolCalls), usage, finishReason, "");
    }

    private ObjectNode buildRequest(ChatRequest request) {
        ObjectNode body = HttpTransport.MAPPER.createObjectNode();
        body.put("model", request.model().modelId());
        ArrayNode messages = body.putArray("messages");
        if (!request.system().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.system());
        }
        for (Message message : request.messages()) {
            appendMessages(messages, message);
        }
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (!request.stop().isEmpty()) {
            ArrayNode stop = body.putArray("stop");
            request.stop().forEach(stop::add);
        }
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        if (request.hasTools()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", spec.name());
                function.put("description", spec.description());
                function.set("parameters", Schemas.parametersSchema(spec));
            }
        }
        request.extra().forEach((key, value) ->
                body.set(key, HttpTransport.MAPPER.valueToTree(value)));
        return body;
    }

    private void appendMessages(ArrayNode out, Message message) {
        switch (message.role()) {
            case SYSTEM -> {
                String text = Schemas.textOf(message);
                if (!text.isBlank()) {
                    out.addObject().put("role", "system").put("content", text);
                }
            }
            case USER -> {
                appendToolResults(out, message);
                String text = Schemas.textOf(message);
                if (!text.isEmpty()) {
                    // image blocks are skipped: this adapter is text-only
                    out.addObject().put("role", "user").put("content", text);
                }
            }
            case ASSISTANT -> {
                appendToolResults(out, message);
                ObjectNode assistant = out.addObject().put("role", "assistant");
                String text = Schemas.textOf(message);
                if (text.isEmpty()) {
                    assistant.putNull("content");
                } else {
                    assistant.put("content", text);
                }
                // reasoning blocks are not sent back to the endpoint
                List<ContentBlock.ToolCall> calls = message.toolCalls();
                if (!calls.isEmpty()) {
                    ArrayNode toolCalls = assistant.putArray("tool_calls");
                    for (ContentBlock.ToolCall call : calls) {
                        ObjectNode node = toolCalls.addObject();
                        node.put("id", call.callId());
                        node.put("type", "function");
                        ObjectNode function = node.putObject("function");
                        function.put("name", call.name());
                        function.put("arguments", call.arguments());
                    }
                }
            }
            case TOOL -> appendToolResults(out, message);
        }
    }

    private void appendToolResults(ArrayNode out, Message message) {
        for (ContentBlock.ToolResult result : message.toolResults()) {
            ObjectNode tool = out.addObject();
            tool.put("role", "tool");
            tool.put("tool_call_id", result.callId());
            tool.put("content", result.text());
        }
    }

    private void accumulateToolCall(JsonNode call, TreeMap<Integer, ToolCallAcc> toolCalls, StreamSink sink) {
        int index = call.path("index").asInt(0);
        JsonNode function = call.get("function");
        ToolCallAcc acc = toolCalls.get(index);
        if (acc == null) {
            acc = new ToolCallAcc();
            toolCalls.put(index, acc);
            String callId = HttpTransport.textOf(call.get("id"));
            String name = function == null ? null : HttpTransport.textOf(function.get("name"));
            acc.id = callId;
            acc.name = name;
            sink.onToolCallStart(index, callId, name);
        } else {
            if (acc.id == null) {
                acc.id = HttpTransport.textOf(call.get("id"));
            }
            if (acc.name == null && function != null) {
                acc.name = HttpTransport.textOf(function.get("name"));
            }
        }
        if (function != null) {
            String fragment = HttpTransport.textOf(function.get("arguments"));
            if (fragment != null && !fragment.isEmpty()) {
                acc.arguments.append(fragment);
                sink.onToolCallArguments(index, fragment);
            }
        }
    }

    private ChatResponse parsePlainResponse(String body, StreamSink sink) {
        JsonNode root;
        try {
            root = HttpTransport.MAPPER.readTree(body);
        } catch (IOException e) {
            throw ProviderException.parse("profile \"" + id() + "\" returned an unparsable response", e);
        }
        String id = orEmpty(HttpTransport.textOf(root.get("id")));
        sink.onStart(id);
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        TreeMap<Integer, ToolCallAcc> toolCalls = new TreeMap<>();

        String content = HttpTransport.textOf(message.get("content"));
        if (content != null && !content.isEmpty()) {
            text.append(content);
            sink.onText(content);
        }
        String reasoningText = HttpTransport.textOf(message.get("reasoning"));
        if (reasoningText == null) {
            reasoningText = HttpTransport.textOf(message.get("reasoning_content"));
        }
        if (reasoningText != null && !reasoningText.isEmpty()) {
            reasoning.append(reasoningText);
            sink.onReasoning(reasoningText);
        }
        JsonNode calls = message.get("tool_calls");
        if (calls != null && calls.isArray()) {
            int index = 0;
            for (JsonNode call : calls) {
                ToolCallAcc acc = new ToolCallAcc();
                acc.id = HttpTransport.textOf(call.get("id"));
                JsonNode function = call.get("function");
                if (function != null) {
                    acc.name = HttpTransport.textOf(function.get("name"));
                    JsonNode arguments = function.get("arguments");
                    if (arguments != null) {
                        acc.arguments.append(arguments.isTextual() ? arguments.asText() : arguments.toString());
                    }
                }
                toolCalls.put(index, acc);
                sink.onToolCallStart(index, acc.id, acc.name);
                if (!acc.arguments.isEmpty()) {
                    sink.onToolCallArguments(index, acc.arguments.toString());
                }
                index++;
            }
        }
        String finishReason = orEmpty(HttpTransport.textOf(choice.get("finish_reason")));
        Usage usage = Usage.EMPTY;
        JsonNode usageNode = root.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            usage = parseUsage(usageNode);
            sink.onUsage(usage);
        }
        sink.onFinish(finishReason);
        return new ChatResponse(id, assembleMessage(reasoning, text, toolCalls), usage, finishReason, body);
    }

    private static Usage parseUsage(JsonNode usage) {
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        int cached = usage.path("prompt_cache_hit_tokens").asInt(-1);
        if (cached < 0) {
            cached = usage.path("cache_read_input_tokens").asInt(0);
        }
        return new Usage(prompt, completion, Math.max(cached, 0));
    }

    private static Message assembleMessage(CharSequence reasoning, CharSequence text,
                                           TreeMap<Integer, ToolCallAcc> toolCalls) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (!reasoning.isEmpty()) {
            blocks.add(new ContentBlock.Reasoning(reasoning.toString()));
        }
        if (!text.isEmpty()) {
            blocks.add(new ContentBlock.Text(text.toString()));
        }
        for (ToolCallAcc acc : toolCalls.values()) {
            blocks.add(new ContentBlock.ToolCall(
                    orEmpty(acc.id), orEmpty(acc.name), acc.arguments.toString()));
        }
        return new Message(null, Role.ASSISTANT, blocks, null);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Accumulates one streamed tool call, keyed by its provider-side index. */
    private static final class ToolCallAcc {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
