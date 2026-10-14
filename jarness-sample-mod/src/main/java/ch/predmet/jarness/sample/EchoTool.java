package ch.predmet.jarness.sample;

import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.Map;

/** {@code sample_echo}: returns its input; proves a dynamically loaded mod's tools work. */
public final class EchoTool implements Tool {

    private final ToolSpec spec = ToolSpec.readOnly("sample_echo",
            "Echo the input text back. Sample tool from the jarness.sample mod.",
            Map.of("text", ToolParameter.required("string", "The text to echo.")));

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        return ToolOutcome.ok("echo: " + invocation.requireString("text"));
    }
}
