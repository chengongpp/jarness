package ch.predmet.jarness.core;

import ch.predmet.jarness.api.JarnessRuntime;
import ch.predmet.jarness.api.config.JarnessLayout;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Entry point for embedding or launching a Jarness runtime.
 *
 * <pre>{@code
 * try (JarnessRuntime runtime = Jarness.builder().preset("default").build()) {
 *     runtime.start();
 *     Agent agent = runtime.spawn("standard", "main", null);
 *     TurnResult result = agent.submit("hello").join();
 * }
 * }</pre>
 */
public final class Jarness {

    /** The runtime version, kept in sync with the Maven project version. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    /** The mod API major version mods declare in {@code jnmods.toml}. */
    public static final String API_VERSION = "1";

    private Jarness() {
    }

    /** @return the runtime version string. */
    public static String version() {
        return VERSION;
    }

    /** @return a runtime builder rooted at the default base directory. */
    public static Builder builder() {
        return new Builder(JarnessLayout.defaultLayout());
    }

    /**
     * Create a runtime with the default base directory and the configured default preset.
     *
     * @return the unstarted runtime
     */
    public static JarnessRuntime create() {
        return builder().build();
    }

    /** Fluent builder for a {@link JarnessRuntime}. */
    public static final class Builder {

        private final JarnessLayout layout;
        private String preset;

        private Builder(JarnessLayout layout) {
            this.layout = layout;
        }

        /**
         * Root the runtime at a different base directory.
         *
         * @param baseDir the base directory holding {@code mods/}, {@code config/}, {@code presets/}
         * @return a new builder for that layout
         */
        public static Builder at(Path baseDir) {
            return new Builder(JarnessLayout.of(baseDir));
        }

        /**
         * Activate a named preset instead of the configured default.
         *
         * @param name preset name
         * @return this builder
         */
        public Builder preset(String name) {
            this.preset = name;
            return this;
        }

        /**
         * Build the runtime. Nothing is loaded or started yet; call {@link JarnessRuntime#start()}.
         *
         * @return the runtime
         */
        public JarnessRuntime build() {
            Objects.requireNonNull(layout, "layout");
            return new JarnessRuntimeImpl(layout, preset);
        }
    }
}
