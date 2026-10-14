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
import java.util.List;
import java.util.Map;

/**
 * {@code read} — read a UTF-8 text file and return line-numbered content.
 */
public final class ReadFileTool implements Tool {

    private static final int DEFAULT_LIMIT = 2000;

    private final ToolSpec spec = ToolSpec.readOnly(
            "read",
            "Read a UTF-8 text file and return line-numbered content.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("file_path", ToolParameter.required("string", "Path to read, resolved by the filesystem backend."));
        params.put("offset", ToolParameter.optional("integer", "1-based first line to return. Defaults to 1."));
        params.put("limit", ToolParameter.optional("integer", "Maximum number of lines to return. Defaults to 2000."));
        return params;
    }

    /** Create the tool. */
    public ReadFileTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            String path = invocation.requireString("file_path");
            int offset = Math.max(1, invocation.integer("offset", 1));
            int limit = invocation.integer("limit", DEFAULT_LIMIT);
            if (limit < 0) {
                limit = DEFAULT_LIMIT;
            }
            Path resolved = ctx.resolve(path, false);
            if (!Files.exists(resolved)) {
                throw new ToolException("file does not exist: " + path);
            }
            if (Files.isDirectory(resolved)) {
                throw new ToolException("path is a directory, not a file: " + path);
            }
            List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
            int total = lines.size();
            int from = Math.min(offset - 1, total);
            int to = Math.min(from + limit, total);
            StringBuilder out = new StringBuilder();
            out.append(resolved).append('\n');
            for (int i = from; i < to; i++) {
                out.append(String.format("%6d: %s%n", i + 1, lines.get(i)));
            }
            if (to >= total) {
                out.append("(End of file - total ").append(total).append(" lines)");
            } else {
                out.append("(truncated - showing lines ").append(from + 1).append("-").append(to)
                        .append(" of ").append(total).append("; use offset to continue)");
            }
            return ToolOutcome.ok(out.toString());
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("read failed: " + e.getMessage());
        }
    }
}
