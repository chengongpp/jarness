package ch.predmet.jarness.core.config;

import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.config.JarnessConfig;
import ch.predmet.jarness.api.config.JarnessLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads {@code <base>/config/jarness.toml} into a {@link JarnessConfig}, applying the defaults of
 * {@link JarnessConfig#defaults(JarnessLayout)} for every absent key.
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    /**
     * Load the runtime configuration for a layout.
     *
     * @param layout the base directory layout
     * @return the resolved configuration; defaults when the config file is absent
     * @throws IllegalStateException when the config file exists but is not valid TOML
     */
    public static JarnessConfig load(JarnessLayout layout) {
        Objects.requireNonNull(layout, "layout");
        if (!Files.exists(layout.configFile())) {
            return JarnessConfig.defaults(layout);
        }
        ConfigView view = TomlFiles.load(layout.configFile());
        JarnessConfig defaults = JarnessConfig.defaults(layout);
        return new JarnessConfig(
                layout,
                view.string("preset", defaults.preset()),
                view.string("defaults.template", defaults.defaultTemplate()),
                view.string("defaults.provider", defaults.defaultProvider()),
                view.string("defaults.model", defaults.defaultModel()),
                view.string("session.store", defaults.sessionStoreUrl()),
                view.integer("limits.max_steps", defaults.maxSteps()),
                view.integer("limits.max_tokens", defaults.maxTokens()),
                temperature(view, defaults.temperature()),
                view.integer("limits.tool_output_max_chars", defaults.toolOutputMaxChars()),
                view.findString("paths.workspace_root").map(Path::of).orElse(defaults.workspaceRoot()),
                view.string("logging.level", defaults.logLevel()),
                view.bool("supervisor.auto_start", defaults.autoStartSupervisor()),
                view);
    }

    private static double temperature(ConfigView view, double fallback) {
        return view.findString("sampling.temperature").map(text -> {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }).orElse(fallback);
    }
}
