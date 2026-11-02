package ch.predmet.jarness.api.session;

import ch.predmet.jarness.api.provider.ModelRef;

import java.nio.file.Path;
import java.util.Map;

/**
 * Creation input for a session.
 *
 * @param id         explicit session id, or {@code null} to generate one
 * @param agentId    owning agent id
 * @param title      initial title, or {@code null} for "untitled"
 * @param workspace  namespace cwd shared by every agent in the namespace
 * @param templateId template the owning agent was created from
 * @param model      model binding, or {@code null}
 * @param metadata   extension metadata
 */
public record SessionInit(String id, String agentId, String title, Path workspace, String templateId,
                          ModelRef model, Map<String, Object> metadata) {

    public SessionInit {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
