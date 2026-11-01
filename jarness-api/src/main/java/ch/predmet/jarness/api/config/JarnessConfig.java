package ch.predmet.jarness.api.config;

import java.nio.file.Path;

/**
 * Resolved runtime configuration: the parsed {@code <base>/config/jarness.toml} with defaults applied.
 *
 * @param layout                 base directory layout
 * @param preset                 preset name to activate when the command line does not name one
 * @param defaultTemplate        template used when an agent does not name one
 * @param defaultProvider        provider profile id used when nothing else selects a model
 * @param defaultModel           model id used when nothing else selects a model
 * @param sessionStoreUrl        JDBC URL of the session store; SQLite by default, replaceable by a mod
 * @param maxSteps               hard cap on provider/tool steps inside one turn
 * @param maxTokens              default {@code max_tokens} for a completion
 * @param temperature            default sampling temperature
 * @param toolOutputMaxChars     truncation cap applied to a single tool observation
 * @param workspaceRoot          root under which per-agent workspaces are created
 * @param logLevel               root log level for the console appender
 * @param autoStartSupervisor    whether {@code Jarness.start()} starts the supervisor by itself
 * @param maxDepth               maximum supervision-tree depth, {@code 0} for unbounded
 * @param maxChildren            maximum direct children per agent, {@code 0} for unbounded
 * @param raw                    the raw configuration document for extension lookups
 */
public record JarnessConfig(
        JarnessLayout layout,
        String preset,
        String defaultTemplate,
        String defaultProvider,
        String defaultModel,
        String sessionStoreUrl,
        int maxSteps,
        int maxTokens,
        double temperature,
        int toolOutputMaxChars,
        Path workspaceRoot,
        String logLevel,
        boolean autoStartSupervisor,
        int maxDepth,
        int maxChildren,
        ConfigView raw) {

    /** @return configuration defaults for a given base directory. */
    public static JarnessConfig defaults(JarnessLayout layout) {
        return new JarnessConfig(
                layout,
                "default",
                "standard",
                "",
                "",
                "jdbc:sqlite:" + layout.sessionDb(),
                64,
                32_000,
                0.0,
                120_000,
                layout.workspacesDir(),
                "info",
                true,
                16,
                32,
                ConfigView.empty());
    }
}
