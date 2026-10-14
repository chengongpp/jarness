package ch.predmet.jarness.core.tool.builtin;

import ch.predmet.jarness.api.agent.SimpleAgentTemplate;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolException;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code template_register} — register a new agent template at runtime, so later
 * {@code agent_spawn}/{@code subagent} calls can create agents from it.
 */
public final class TemplateRegisterTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating(
            "template_register",
            "Register a new agent template at runtime.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("id", ToolParameter.required("string", "Template id, unique in the template registry."));
        params.put("system_prompt", ToolParameter.required("string", "System prompt preamble for agents of this template."));
        params.put("tools", ToolParameter.optional("array", "Tool names the template exposes, in prompt order."));
        params.put("description", ToolParameter.optional("string", "One-liner shown in template listings."));
        params.put("dynamic_mods", ToolParameter.optional("boolean",
                "Whether agents of this template may load mods at runtime. Defaults to false."));
        return params;
    }

    /** Create the tool. */
    public TemplateRegisterTool() {
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        try {
            String id = invocation.requireString("id");
            String systemPrompt = invocation.requireString("system_prompt");
            List<String> tools = invocation.stringList("tools");
            String description = invocation.string("description", "");
            boolean dynamicMods = invocation.bool("dynamic_mods", false);
            SimpleAgentTemplate template = SimpleAgentTemplate.builder(id)
                    .systemPrompt(systemPrompt)
                    .description(description)
                    .tools(tools)
                    .dynamicMods(dynamicMods)
                    .build();
            ctx.runtime().templates().register(template);
            return ToolOutcome.ok("registered template " + template.id()
                    + (tools.isEmpty() ? "" : " with tools " + tools));
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            return ToolOutcome.error("template_register failed: " + e.getMessage());
        }
    }
}
