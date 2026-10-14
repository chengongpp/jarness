package ch.predmet.jarness.core.agent;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the system prompt and the outgoing conversation for one agent.
 *
 * <p>The system prompt layers, in order: the Jarness identity line, the template preamble, and
 * the agent's registered prompt sections in ascending order. The environment and tool-convention
 * sections are contributed as regular sections by the core mod's {@code prompt.baseline}
 * instrument, so a preset can strip them. The conversation is the session history plus, when
 * user-context sections are registered, one synthetic trailing user message titled
 * {@code # Context} that is never persisted.</p>
 */
public final class PromptAssembler {

    private PromptAssembler() {
    }

    /**
     * Render the full system prompt for one request.
     *
     * @param agent   the requesting agent
     * @param runtime the running runtime
     * @return the assembled system prompt
     */
    public static String systemPrompt(Agent agent, JarnessRuntime runtime) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI agent powered by Jarness.");

        String templatePrompt = agent.template().systemPrompt();
        if (templatePrompt != null && !templatePrompt.isBlank()) {
            sb.append("\n\n").append(templatePrompt.strip());
        }

        if (agent.ctx() instanceof AgentContextImpl ctx) {
            for (String section : ctx.systemPromptTexts()) {
                if (!section.isBlank()) {
                    sb.append("\n\n").append(section.strip());
                }
            }
        }
        return sb.toString();
    }

    /**
     * Build the outgoing conversation: the session history plus one synthetic trailing user
     * message with the current user-context sections, when any are registered.
     *
     * @param agent   the requesting agent
     * @param session the session to read the history from
     * @return the messages to send; the trailing context message is not persisted
     */
    public static List<Message> messages(Agent agent, Session session) {
        List<Message> history = session.messages();
        List<String> contexts = agent.ctx() instanceof AgentContextImpl ctx
                ? ctx.userContextTexts()
                : List.of();
        if (contexts.isEmpty()) {
            return history;
        }
        StringBuilder sb = new StringBuilder("# Context");
        for (String text : contexts) {
            sb.append("\n\n").append(text.strip());
        }
        List<Message> out = new ArrayList<>(history.size() + 1);
        out.addAll(history);
        out.add(Message.user(sb.toString()));
        return out;
    }
}
