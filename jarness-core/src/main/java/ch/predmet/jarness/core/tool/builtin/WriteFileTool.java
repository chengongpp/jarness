package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code write} — create or fully replace a UTF-8 text file. Writes go through
 * {@link ToolContext#resolve(String, boolean)} with write intent, so the sandbox is enforced.
 */
public final class WriteFileTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating(
            "write",
            "Create or fully replace a UTF-8 text file.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("file_path", ToolParameter.required("string", "Path to write, resolved by the filesystem backend."));
        params.put("content", ToolParameter.required("string", "The full UTF-8 text content to write."));
        return params;
    }

    /** Create the tool. */
    public WriteFileTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            String path = invocation.requireString("file_path");
            String content = invocation.requireString("content");
            Path resolved = ctx.resolve(path, true);
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return ToolOutcome.ok("wrote " + resolved + " (" + content.length() + " chars)");
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("write failed: " + e.getMessage());
        }
    }
}
