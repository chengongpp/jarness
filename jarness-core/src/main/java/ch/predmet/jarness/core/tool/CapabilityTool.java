package ch.predmet.jarness.core.tool;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolForm;
import ch.predmet.jarness.api.tool.ToolMode;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A tool decorator that declares the capabilities a built-in tool needs.
 *
 * <p>Built-in tools are plain implementations that focus on doing one thing; the core mod wraps
 * each one with the capability kinds it requires, so the dispatcher can refuse a call before the
 * tool body runs. Mods can either override {@link Tool#requiredCaps()} directly or use this
 * wrapper.</p>
 */
public final class CapabilityTool implements Tool {

    private final Tool delegate;
    private final Set<CapKind> required;

    private CapabilityTool(Tool delegate, Set<CapKind> required) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.required = Set.copyOf(required);
    }

    /**
     * Wrap a tool.
     *
     * @param delegate the tool
     * @param caps     the capability kinds it requires
     * @return the wrapped tool
     */
    public static Tool of(Tool delegate, CapKind... caps) {
        Objects.requireNonNull(caps, "caps");
        if (caps.length == 0) {
            return delegate;
        }
        EnumSet<CapKind> set = EnumSet.noneOf(CapKind.class);
        for (CapKind cap : caps) {
            if (cap != null) {
                set.add(cap);
            }
        }
        return new CapabilityTool(delegate, set);
    }

    @Override
    public ToolSpec spec() {
        return delegate.spec();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) throws Exception {
        return delegate.invoke(invocation, ctx);
    }

    @Override
    public Set<CapKind> requiredCaps() {
        return required;
    }

    @Override
    public ToolForm form() {
        return delegate.form();
    }

    @Override
    public ToolMode mode() {
        return delegate.mode();
    }

    @Override
    public String relayName() {
        return delegate.relayName();
    }
}
