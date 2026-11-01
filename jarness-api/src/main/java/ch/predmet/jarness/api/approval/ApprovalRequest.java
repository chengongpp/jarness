package ch.predmet.jarness.api.approval;

import java.util.Map;

/**
 * One side-effecting tool call presented for approval.
 *
 * @param agentId     calling agent
 * @param namespaceId calling namespace
 * @param tool        tool name
 * @param arguments   parsed arguments, for the handler to display
 * @param summary     short human-readable description
 */
public record ApprovalRequest(String agentId, String namespaceId, String tool,
                              Map<String, Object> arguments, String summary) {

    public ApprovalRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        summary = summary == null ? "" : summary;
    }
}
