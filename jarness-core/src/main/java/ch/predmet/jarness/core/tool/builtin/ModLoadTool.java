package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.mod.LoadReport;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code mod_load} — load a mod jar into the running runtime. Only agents whose template allows
 * dynamic mods may call it. Loading is permanent; unloading is not supported.
 */
public final class ModLoadTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating(
            "mod_load",
            "Load a mod jar into the running runtime. Loading is permanent; unloading is not supported.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("path", ToolParameter.required("string", "Path to the mod jar."));
        return params;
    }

    /** Create the tool. */
    public ModLoadTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            if (!ctx.agent().template().dynamicMods()) {
                return ToolOutcome.denied("the " + ctx.agent().template().id() + " template may not load mods");
            }
            String path = invocation.requireString("path");
            Path resolved = ctx.resolve(path, false);
            if (!Files.exists(resolved)) {
                throw new ToolException("file does not exist: " + path);
            }
            if (!resolved.getFileName().toString().endsWith(".jar")) {
                throw new ToolException("not a jar file: " + path);
            }
            LoadReport report = ctx.runtime().modLoader().loadJar(resolved, true);
            String rendered = report.render();
            if (!report.ok()) {
                return ToolOutcome.error("mod load failed", rendered);
            }
            StringBuilder out = new StringBuilder();
            if (!rendered.isBlank()) {
                out.append(rendered);
            }
            out.append("Loaded mods: ").append(report.loadedIds());
            return ToolOutcome.ok(out.toString());
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("mod_load failed: " + e.getMessage());
        }
    }
}
