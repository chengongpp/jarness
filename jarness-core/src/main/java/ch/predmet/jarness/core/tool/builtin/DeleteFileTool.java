package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code delete}: remove a file or an empty directory, sandbox enforced.
 */
public final class DeleteFileTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating("delete",
            "Delete a file or an empty directory.",
            Map.of("file_path", ToolParameter.required("string", "Path of the file or empty directory to delete.")));

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        Path target = ctx.resolve(invocation.requireString("file_path"), true);
        try {
            if (!Files.exists(target)) {
                throw new ToolException("no such file: " + target);
            }
            Files.delete(target);
            return ToolOutcome.ok("deleted " + target);
        } catch (java.nio.file.DirectoryNotEmptyException e) {
            throw new ToolException("directory is not empty: " + target + " (delete its contents first)");
        } catch (IOException e) {
            return ToolOutcome.error("delete failed: " + e.getMessage());
        }
    }
}
