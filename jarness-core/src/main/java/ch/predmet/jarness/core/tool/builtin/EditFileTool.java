package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.capability.CapKind;
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
 * {@code edit} — edit an existing UTF-8 text file by replacing literal text.
 */
public final class EditFileTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating(
            "edit",
            "Edit an existing UTF-8 text file by replacing literal text.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("file_path", ToolParameter.required("string", "Path to edit, resolved by the filesystem backend."));
        params.put("old_string", ToolParameter.required("string", "Literal text to replace."));
        params.put("new_string", ToolParameter.required("string", "Literal replacement text. Use an empty string to delete the match."));
        params.put("replace_all", ToolParameter.optional("boolean",
                "Replace all matches. Defaults to false; when false, old_string must appear exactly once."));
        return params;
    }

    /** Create the tool. */
    public EditFileTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            String path = invocation.requireString("file_path");
            String oldString = invocation.requireString("old_string");
            String newString = invocation.string("new_string", null);
            if (newString == null) {
                throw new ToolException("missing required parameter \"new_string\"");
            }
            boolean replaceAll = invocation.bool("replace_all", false);
            Path resolved = ctx.resolveScoped(path, CapKind.FS_WRITE, true);
            if (!Files.exists(resolved)) {
                throw new ToolException("file does not exist: " + path);
            }
            if (Files.isDirectory(resolved)) {
                throw new ToolException("path is a directory, not a file: " + path);
            }
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            int occurrences = countOccurrences(content, oldString);
            if (occurrences == 0) {
                throw new ToolException("old_string not found in " + path);
            }
            if (!replaceAll && occurrences != 1) {
                throw new ToolException("old_string appears " + occurrences + " times in " + path
                        + "; pass replace_all: true or make the match unique");
            }
            String updated = replaceAll
                    ? content.replace(oldString, newString)
                    : content.substring(0, content.indexOf(oldString))
                    + newString
                    + content.substring(content.indexOf(oldString) + oldString.length());
            Files.writeString(resolved, updated, StandardCharsets.UTF_8);
            return ToolOutcome.ok("edited " + resolved + " (" + occurrences + " occurrence"
                    + (occurrences == 1 ? "" : "s") + " replaced)");
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("edit failed: " + e.getMessage());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
