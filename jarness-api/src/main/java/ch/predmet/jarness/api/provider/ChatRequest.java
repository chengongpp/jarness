package ch.predmet.jarness.api.provider;

import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * One completion request, fully assembled and ready for a provider adapter.
 *
 * @param model       target model
 * @param system      rendered system prompt
 * @param messages    conversation, oldest first; excludes the system prompt
 * @param tools       tool schemas to advertise
 * @param maxTokens   completion budget
 * @param temperature sampling temperature, or {@code null} for the provider default
 * @param stop        stop sequences, empty when unused
 * @param extra       provider-specific escape hatch, merged into the request body
 */
public record ChatRequest(ModelRef model, String system, List<Message> messages, List<ToolSpec> tools,
                          int maxTokens, Double temperature, List<String> stop, Map<String, Object> extra) {

    public ChatRequest {
        system = system == null ? "" : system;
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        stop = stop == null ? List.of() : List.copyOf(stop);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /** @return a request with no tools and the given system prompt. */
    public static ChatRequest of(ModelRef model, String system, List<Message> messages, int maxTokens) {
        return new ChatRequest(model, system, messages, List.of(), maxTokens, null, List.of(), Map.of());
    }

    /** @return a copy carrying another model. */
    public ChatRequest withModel(ModelRef newModel) {
        return new ChatRequest(newModel, system, messages, tools, maxTokens, temperature, stop, extra);
    }

    /** @return whether the request advertises any tool. */
    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
