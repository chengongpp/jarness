package ch.predmet.jarness.core.templates;

import ch.predmet.jarness.api.agent.AgentTemplate;
import ch.predmet.jarness.api.agent.AgentTemplateRegistry;
import ch.predmet.jarness.api.agent.SimpleAgentTemplate;

import java.util.List;

/**
 * The four built-in agent templates.
 *
 * <ul>
 *   <li>{@code void} — a plain chatbot, no tools at all.</li>
 *   <li>{@code minimal} — the basic file and exec tools: read, write, delete, bash plus job control.</li>
 *   <li>{@code standard} — minimal plus edit/glob/grep, web access, and subagent delegation.</li>
 *   <li>{@code full} — standard plus runtime extension: dynamic mod loading, template authoring,
 *       and agent spawning.</li>
 * </ul>
 *
 * <p>Nothing about the four is privileged; they are registered like any other template and a mod
 * may replace or wrap them.</p>
 */
public final class BuiltinTemplates {

    /** Template id of the tool-less chatbot. */
    public static final String VOID = "void";
    /** Template id of the basic file/exec agent. */
    public static final String MINIMAL = "minimal";
    /** Template id of the standard working agent. */
    public static final String STANDARD = "standard";
    /** Template id of the fully capable, runtime-extending agent. */
    public static final String FULL = "full";
    /** Template id of the platform-level super agent. */
    public static final String SUPER = "super";

    private BuiltinTemplates() {
    }

    /** @return the void template. */
    public static AgentTemplate voidTemplate() {
        return SimpleAgentTemplate.builder(VOID)
                .name("Void")
                .description("A plain chatbot with no tools.")
                .systemPrompt("You are a helpful assistant. Answer directly and concisely.")
                .tools(List.of())
                .build();
    }

    /** @return the minimal template. */
    public static AgentTemplate minimal() {
        return SimpleAgentTemplate.builder(MINIMAL)
                .name("Minimal")
                .description("Basic file and shell tools: read, write, delete, bash, job control.")
                .systemPrompt("You are a capable assistant with basic file and shell access. "
                        + "Prefer the dedicated tools over shell commands for file operations.")
                .tools("read", "write", "delete", "bash", "job_output", "job_kill", "job_list")
                .build();
    }

    /** @return the standard template. */
    public static AgentTemplate standard() {
        return SimpleAgentTemplate.builder(STANDARD)
                .name("Standard")
                .description("The everyday working agent: files, search, web, and subagents.")
                .systemPrompt("""
                        You are an autonomous agent that gets tasks done inside its workspace.
                        Work incrementally, verify assumptions by reading before writing, and \
                        delegate self-contained subtasks with the subagent tool when that keeps \
                        the main line of work focused.""")
                .tools("read", "write", "edit", "delete", "glob", "grep", "bash",
                        "job_output", "job_kill", "job_list",
                        "web_search", "web_fetch", "subagent", "shm", "agent_send")
                .build();
    }

    /** @return the full template. */
    public static AgentTemplate full() {
        return SimpleAgentTemplate.builder(FULL)
                .name("Full")
                .description("Everything Standard has, plus dynamic mod loading, template authoring, and agent spawning.")
                .systemPrompt("""
                        You are an autonomous agent with full access to the runtime itself.
                        You may load mod jars at runtime, author new agent templates, and spawn \
                        purpose-built sub-agents from them. Use these powers deliberately: a \
                        loaded mod cannot be unloaded, and a spawned agent shares the host's \
                        resources.""")
                .tools("read", "write", "edit", "delete", "glob", "grep", "bash",
                        "job_output", "job_kill", "job_list",
                        "web_search", "web_fetch", "subagent", "shm", "agent_send",
                        "mod_load", "template_register", "agent_spawn")
                .dynamicMods(true)
                .build();
    }

    /** @return the platform-level super agent template. */
    public static AgentTemplate superTemplate() {
        return SimpleAgentTemplate.builder(SUPER)
                .name("Super")
                .description("Platform operator: namespaces, daemon status, and the supervision tree.")
                .systemPrompt("""
                        You are the Jarness super agent, the only agent holding platform capabilities.
                        You can list and manage namespaces, inspect the supervision tree and daemon status,
                        and signal or kill other agents. Prefer the dedicated tools over guessing; report
                        what you changed and keep the tree healthy.""")
                .tools("ns_list", "ns_create", "ns_remove",
                        "daemon_status", "agent_tree", "agent_kill", "agent_signal",
                        "agent_spawn", "subagent", "shm", "agent_send")
                .build();
    }

    /**
     * Register all built-in templates.
     *
     * @param registry the template registry
     */
    public static void registerAll(AgentTemplateRegistry registry) {
        registry.register(voidTemplate());
        registry.register(minimal());
        registry.register(standard());
        registry.register(full());
        registry.register(superTemplate());
    }
}
