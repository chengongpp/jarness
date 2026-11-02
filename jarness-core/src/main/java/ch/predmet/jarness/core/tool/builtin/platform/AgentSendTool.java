package ch.predmet.jarness.core.tool.builtin.platform;

import ch.predmet.jarness.api.agent.Agent;
import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.mailbox.MailboxMessage;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolForm;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code agent_send} — put a data message directly into another agent's in-JVM mailbox.
 *
 * <p>This is the message half of IPC and is deliberately {@link ToolForm#LOCAL}: the delivery is a
 * queue offer inside the daemon, never an executor call. The receiver handles the message as a new
 * turn through its normal mailbox loop.</p>
 */
public final class AgentSendTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating(
            "agent_send",
            "Send a message directly to another agent's mailbox; it becomes a new turn there.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("pid", ToolParameter.required("integer", "Target agent pid."));
        params.put("message", ToolParameter.required("string", "Message text to deliver."));
        return params;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public Set<CapKind> requiredCaps() {
        return Set.of(CapKind.IPC);
    }

    @Override
    public ToolForm form() {
        return ToolForm.LOCAL;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        long pid = invocation.integer("pid", -1);
        if (pid < 0) {
            return ToolOutcome.error("missing required parameter \"pid\"");
        }
        String message = invocation.string("message", "");
        if (message.isBlank()) {
            return ToolOutcome.error("message must not be blank");
        }
        if (pid == ctx.agent().pid()) {
            return ToolOutcome.error("cannot send a mailbox message to the calling agent itself");
        }
        Agent target;
        try {
            target = ctx.runtime().supervisor().agent(pid);
        } catch (IllegalArgumentException e) {
            return ToolOutcome.error("unknown pid " + pid);
        }
        if (!target.namespaceId().equals(ctx.agent().namespaceId())
                && !ctx.capabilities().containsKind(CapKind.NAMESPACE)
                && !ctx.capabilities().containsKind(CapKind.PLATFORM)) {
            return ToolOutcome.denied("agent " + pid + " is in namespace " + target.namespaceId()
                    + "; cross-namespace messaging needs the namespace capability");
        }
        boolean accepted = target.deliver(MailboxMessage.data(ctx.agent().pid(), message));
        if (!accepted) {
            return ToolOutcome.error("mailbox of pid " + pid + " is full or closed");
        }
        return ToolOutcome.ok("delivered to pid " + pid + "; it will run as a new turn");
    }
}
