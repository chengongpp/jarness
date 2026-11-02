package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * {@code grep} — search file contents with a regular expression. Text files only: anything with a
 * NUL in its first 8KB or larger than 2MB is skipped. Matches are grouped by file and capped at
 * 250.
 */
public final class GrepTool implements Tool {

    private static final int MAX_MATCHES = 250;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int SNIFF_BYTES = 8192;

    private final ToolSpec spec = ToolSpec.readOnly(
            "grep",
            "Search file contents with a regular expression.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("pattern", ToolParameter.required("string", "Regular expression to search for."));
        params.put("path", ToolParameter.optional("string",
                "File or directory to search. Defaults to the namespace cwd."));
        params.put("include", ToolParameter.optional("string",
                "Glob filter on file names, e.g. \"*.java\"."));
        return params;
    }

    /** Create the tool. */
    public GrepTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            Pattern pattern;
            try {
                pattern = Pattern.compile(invocation.requireString("pattern"));
            } catch (PatternSyntaxException e) {
                throw new ToolException("invalid regular expression: " + e.getMessage());
            }
            Path root = invocation.findString("path")
                    .map(p -> ctx.resolve(p, false))
                    .orElse(ctx.workspace());
            if (!Files.exists(root)) {
                throw new ToolException("search path does not exist: " + root);
            }
            PathMatcher include = invocation.findString("include")
                    .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                    .orElse(null);

            List<Path> files = new ArrayList<>();
            if (Files.isRegularFile(root)) {
                files.add(root);
            } else {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile).forEach(files::add);
                }
            }
            Path base = Files.isRegularFile(root) ? root.getParent() : root;

            StringBuilder out = new StringBuilder();
            int matches = 0;
            int filesWithMatches = 0;
            boolean truncated = false;
            for (Path file : files) {
                if (include != null && !include.matches(file.getFileName())) {
                    continue;
                }
                if (Files.size(file) > MAX_FILE_BYTES || looksBinary(file)) {
                    continue;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (MalformedInputException e) {
                    continue;
                }
                String relative = base != null && file.startsWith(base)
                        ? base.relativize(file).toString()
                        : file.toString();
                boolean fileMatched = false;
                for (int i = 0; i < lines.size(); i++) {
                    if (!pattern.matcher(lines.get(i)).find()) {
                        continue;
                    }
                    if (matches >= MAX_MATCHES) {
                        truncated = true;
                        break;
                    }
                    if (!fileMatched) {
                        filesWithMatches++;
                        fileMatched = true;
                    }
                    out.append(relative).append(':').append(i + 1).append(": ").append(lines.get(i)).append('\n');
                    matches++;
                }
                if (truncated) {
                    break;
                }
            }
            if (matches == 0) {
                return ToolOutcome.ok("no matches");
            }
            if (truncated) {
                out.append("(truncated at ").append(MAX_MATCHES).append(" matches)");
            } else {
                out.append("(").append(matches).append(matches == 1 ? " match" : " matches")
                        .append(" in ").append(filesWithMatches).append(filesWithMatches == 1 ? " file" : " files")
                        .append(")");
            }
            return ToolOutcome.ok(out.toString());
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("grep failed: " + e.getMessage());
        }
    }

    private static boolean looksBinary(Path file) {
        byte[] buffer = new byte[SNIFF_BYTES];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.read(buffer);
        } catch (IOException e) {
            return true;
        }
        for (int i = 0; i < read; i++) {
            if (buffer[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
