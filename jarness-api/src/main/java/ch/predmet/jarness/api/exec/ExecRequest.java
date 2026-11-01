package ch.predmet.jarness.api.exec;

import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.tool.ToolMode;

import java.time.Instant;
import java.util.Map;

/**
 * One tool invocation handed to an execution provider.
 *
 * <p>The request carries everything the executor needs to enforce its side of the contract: the
 * tool name on the executor, the parsed arguments, the deadline, the calling agent's workspace and
 * sandbox, and the capability view. The daemon never asks the executor to trust JVM state.</p>
 *
 * @param callId          call id, unique within the runtime
 * @param tool            executor tool name, e.g. {@code fs.read}
 * @param args            parsed arguments
 * @param deadline        absolute deadline, or {@code null}
 * @param mode            sync or async
 * @param idempotencyKey  key used to deduplicate retries, or {@code null}
 * @param workspace       calling agent's workspace
 * @param sandbox         sandbox mode name
 * @param capabilities    capability view granted to the call
 * @param namespace       calling agent's namespace
 */
public record ExecRequest(String callId, String tool, Map<String, Object> args, Instant deadline,
                          ToolMode mode, String idempotencyKey, String workspace, String sandbox,
                          CapabilitySet capabilities, String namespace) {

    public ExecRequest {
        if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("tool must not be blank");
        }
        args = args == null ? Map.of() : Map.copyOf(args);
        mode = mode == null ? ToolMode.SYNC : mode;
        sandbox = sandbox == null ? "workspace-write" : sandbox;
        capabilities = capabilities == null ? CapabilitySet.empty() : capabilities;
        namespace = namespace == null ? "default" : namespace;
    }
}
