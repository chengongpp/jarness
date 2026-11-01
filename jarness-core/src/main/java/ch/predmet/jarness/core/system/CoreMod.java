package ch.predmet.jarness.core.system;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.hook.HookPoint;
import ch.predmet.jarness.api.hook.event.AgentEvent;
import ch.predmet.jarness.api.mod.JarnessMod;
import ch.predmet.jarness.api.mod.ModContext;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.provider.ProviderProfile;
import ch.predmet.jarness.core.provider.ProfileLoader;
import ch.predmet.jarness.core.provider.ProviderFactory;
import ch.predmet.jarness.core.templates.BuiltinTemplates;
import ch.predmet.jarness.core.tool.CapabilityTool;
import ch.predmet.jarness.core.tool.builtin.AgentSpawnTool;
import ch.predmet.jarness.core.tool.builtin.BashTool;
import ch.predmet.jarness.core.tool.builtin.DeleteFileTool;
import ch.predmet.jarness.core.tool.builtin.EditFileTool;
import ch.predmet.jarness.core.tool.builtin.GlobTool;
import ch.predmet.jarness.core.tool.builtin.GrepTool;
import ch.predmet.jarness.core.tool.builtin.JobKillTool;
import ch.predmet.jarness.core.tool.builtin.JobListTool;
import ch.predmet.jarness.core.tool.builtin.JobOutputTool;
import ch.predmet.jarness.core.tool.builtin.ModLoadTool;
import ch.predmet.jarness.core.tool.builtin.ReadFileTool;
import ch.predmet.jarness.core.tool.builtin.SubagentTool;
import ch.predmet.jarness.core.tool.builtin.TemplateRegisterTool;
import ch.predmet.jarness.core.tool.builtin.WebFetchTool;
import ch.predmet.jarness.core.tool.builtin.WebSearchTool;
import ch.predmet.jarness.core.tool.builtin.WriteFileTool;

import java.time.LocalDate;
import java.util.List;

/**
 * The core system mod ({@code jarness.core}): wires the runtime's own capabilities as instruments
 * so that presets can switch them individually — providers, the builtin tool groups, the baseline
 * prompt sections, and the four built-in templates.
 *
 * <p>It lives on the classpath, is marked required, and is provided through the
 * {@code ModProvider} SPI so it survives shading into a single application jar.</p>
 */
public final class CoreMod implements JarnessMod {

    /** The core mod id. */
    public static final String ID = "jarness.core";

    @Override
    public void register(ModContext ctx) {
        ctx.instruments().register("providers", true, 100,
                "Load provider profiles from config/providers.toml and pick the default model.", ic -> {
                    List<ProviderProfile> profiles = ProfileLoader.load(ic.runtime().layout().providersFile());
                    for (ProviderProfile profile : profiles) {
                        ic.providers().register(ProviderFactory.create(profile));
                        ic.log().info("provider {} ({}) with {} model(s)", profile.id(), profile.kind().id(),
                                profile.models().size());
                    }
                    String provider = ic.runtime().config().defaultProvider();
                    String model = ic.runtime().config().defaultModel();
                    if (!provider.isBlank() && !model.isBlank()) {
                        ic.providers().setDefault(new ModelRef(provider, model));
                    } else if (profiles.size() == 1 && !profiles.get(0).models().isEmpty()) {
                        ProviderProfile only = profiles.get(0);
                        ic.providers().setDefault(new ModelRef(only.id(), only.firstModelId()));
                    } else if (!profiles.isEmpty() && ic.providers().defaultModel().isEmpty()) {
                        ic.log().warn("no default model configured; set defaults.provider/defaults.model "
                                + "in config/jarness.toml");
                    }
                });

        ctx.instruments().register("tools.fs", true, 200,
                "Filesystem tools: read, write, edit, delete, glob, grep.", ic -> {
                    ic.tools().register(CapabilityTool.of(new ReadFileTool(), CapKind.FS_READ));
                    ic.tools().register(CapabilityTool.of(new WriteFileTool(), CapKind.FS_WRITE));
                    ic.tools().register(CapabilityTool.of(new EditFileTool(), CapKind.FS_WRITE));
                    ic.tools().register(CapabilityTool.of(new DeleteFileTool(), CapKind.FS_WRITE));
                    ic.tools().register(CapabilityTool.of(new GlobTool(), CapKind.FS_READ));
                    ic.tools().register(CapabilityTool.of(new GrepTool(), CapKind.FS_READ));
                });

        ctx.instruments().register("tools.exec", true, 210,
                "Shell execution and background job control: bash, job_output, job_kill, job_list.", ic -> {
                    ic.tools().register(CapabilityTool.of(new BashTool(), CapKind.EXEC));
                    ic.tools().register(CapabilityTool.of(new JobOutputTool(), CapKind.EXEC));
                    ic.tools().register(CapabilityTool.of(new JobKillTool(), CapKind.EXEC));
                    ic.tools().register(CapabilityTool.of(new JobListTool(), CapKind.EXEC));
                });

        ctx.instruments().register("tools.web", true, 220,
                "Web access: web_search, web_fetch.", ic -> {
                    ic.tools().register(CapabilityTool.of(new WebSearchTool(), CapKind.NET));
                    ic.tools().register(CapabilityTool.of(new WebFetchTool(), CapKind.NET));
                });

        ctx.instruments().register("tools.agent", true, 230,
                "Agent and runtime tools: subagent, mod_load, template_register, agent_spawn.", ic -> {
                    ic.tools().register(CapabilityTool.of(new SubagentTool(), CapKind.SPAWN));
                    ic.tools().register(CapabilityTool.of(new ModLoadTool(), CapKind.EXTEND));
                    ic.tools().register(CapabilityTool.of(new TemplateRegisterTool(), CapKind.EXTEND));
                    ic.tools().register(CapabilityTool.of(new AgentSpawnTool(), CapKind.SPAWN));
                });

        ctx.instruments().register("prompt.baseline", true, 300,
                "Baseline system-prompt sections: environment and tool-usage conventions.", ic ->
                    ic.hooks().on(HookPoint.AGENT_CREATED, "jarness.core:prompt.baseline", 100, hookCtx -> {
                        AgentEvent event = hookCtx.payload(AgentEvent.class);
                        var agentCtx = event.agent().ctx();
                        agentCtx.systemPromptSection("environment", 900, () -> """
                                # Environment
                                Working directory: %s — relative paths resolve against it. This is NOT the runtime base directory.
                                Namespace: %s (agent pid %d, depth %d; capabilities: %s)
                                Sandbox mode: %s
                                Current date: %s
                                """.formatted(
                                event.agent().workspace(),
                                event.agent().namespaceId(),
                                event.agent().pid(),
                                event.agent().depth(),
                                event.agent().capabilities().kinds(),
                                ic.runtime().config().raw().string("sandbox.mode", "workspace-write"),
                                LocalDate.now()));
                        agentCtx.systemPromptSection("tool-conventions", 950, () -> {
                            if (event.agent().ctx().visibleToolNames().isEmpty()) {
                                return "";
                            }
                            return """
                                    # Tool conventions
                                    - Use the read tool — not shell commands like cat — to inspect text files.
                                    - Use glob to discover files by path pattern and grep to search file contents; reach for bash only when a dedicated tool does not fit.
                                    - Read a file before editing it; prefer edit for targeted changes and write for full replacements.
                                    - When run_in_background is used, collect results later with job_output and stop stale jobs with job_kill.
                                    """;
                        });
                    }));

        ctx.instruments().register("templates", true, 400,
                "The four built-in agent templates: void, minimal, standard, full.",
                ic -> BuiltinTemplates.registerAll(ic.templates()));
    }
}
