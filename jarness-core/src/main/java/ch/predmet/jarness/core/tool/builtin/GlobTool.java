package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code glob} — find files, not directories, whose paths match a glob pattern. Results are sorted
 * by last-modified time, newest first, and capped at 100 entries.
 */
public final class GlobTool implements Tool {

    private static final int MAX_RESULTS = 100;

    private final ToolSpec spec = ToolSpec.readOnly(
            "glob",
            "Find files, not directories, whose paths match a glob pattern.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("pattern", ToolParameter.required("string",
                "Glob pattern, e.g. \"**/*.ts\". A pattern with no \"/\" matches the basename at any depth."));
        params.put("path", ToolParameter.optional("string",
                "Directory to search in. Defaults to the namespace cwd."));
        return params;
    }

    /** Create the tool. */
    public GlobTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            String pattern = invocation.requireString("pattern");
            PathMatcher matcher;
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            } catch (IllegalArgumentException e) {
                throw new ToolException("invalid glob pattern: " + e.getMessage());
            }
            Path root = invocation.findString("path")
                    .map(p -> ctx.resolve(p, false))
                    .orElse(ctx.workspace());
            if (!Files.isDirectory(root)) {
                throw new ToolException("search root is not a directory: " + root);
            }
            boolean basenameOnly = !pattern.contains("/");
            List<Path> matches = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    Path relative = root.relativize(file);
                    if (matcher.matches(relative)
                            || (basenameOnly && matcher.matches(file.getFileName()))) {
                        matches.add(file);
                    }
                });
            }
            matches.sort(Comparator.comparingLong(GlobTool::lastModified).reversed());
            int total = matches.size();
            List<Path> shown = matches.size() > MAX_RESULTS ? matches.subList(0, MAX_RESULTS) : matches;
            StringBuilder out = new StringBuilder();
            for (Path match : shown) {
                out.append(root.relativize(match)).append('\n');
            }
            if (total > MAX_RESULTS) {
                out.append("(truncated - showing ").append(MAX_RESULTS).append(" of ").append(total)
                        .append(" matches, newest first)");
            } else {
                out.append("(").append(total).append(total == 1 ? " file" : " files").append(")");
            }
            return ToolOutcome.ok(out.toString());
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("glob failed: " + e.getMessage());
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
