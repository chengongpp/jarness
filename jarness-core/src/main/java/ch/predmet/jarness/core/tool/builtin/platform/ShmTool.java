package ch.predmet.jarness.core.tool.builtin.platform;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.ipc.Cell;
import ch.predmet.jarness.api.ipc.Shm;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolForm;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code shm} — in-JVM shared memory for agent collaboration.
 *
 * <p>The tool is intentionally {@link ToolForm#LOCAL}: cells live in the daemon's memory and are
 * written through to SQLite by the same JVM. It never goes through an executor, so collaboration
 * works even when no execution provider is alive.</p>
 */
public final class ShmTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolSpec spec = ToolSpec.mutating(
            "shm",
            "Read and write namespace-scoped shared memory cells. Ops: create, read, write, cas, "
                    + "delete, list, snapshot.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("op", ToolParameter.required("string",
                "create, read, write, cas, delete, list, or snapshot."));
        params.put("name", ToolParameter.optional("string",
                "Shm name. Required for every op except a bare list."));
        params.put("cell", ToolParameter.optional("string", "Cell name for read/write/cas/delete."));
        params.put("value", ToolParameter.optional("object",
                "JSON value to store. Strings, numbers, arrays and objects are accepted."));
        params.put("expected_version", ToolParameter.optional("integer",
                "For cas: expected version, 0 meaning the cell must not exist."));
        return params;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public Set<CapKind> requiredCaps() {
        return Set.of(CapKind.IPC);
    }

    @Override
    public ToolForm form() {
        return ToolForm.LOCAL;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        String op = invocation.requireString("op").trim().toLowerCase();
        String name = invocation.string("name", "").trim();
        try {
            return switch (op) {
                case "list" -> list(invocation, ctx, name);
                case "create" -> create(ctx, name);
                case "read" -> read(ctx, name, requiredCell(invocation));
                case "write" -> write(invocation, ctx, name);
                case "cas" -> cas(invocation, ctx, name);
                case "delete" -> delete(ctx, name, requiredCell(invocation));
                case "snapshot" -> snapshot(ctx, name);
                default -> ToolOutcome.error("unknown shm op \"" + op
                        + "\"; expected create, read, write, cas, delete, list, or snapshot");
            };
        } catch (SecurityException e) {
            return ToolOutcome.denied(e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ToolOutcome.error(e.getMessage());
        }
    }

    private ToolOutcome list(ToolInvocation invocation, ToolContext ctx, String name) {
        String namespace = ctx.agent().namespaceId();
        if (name.isBlank()) {
            List<String> ids = ctx.runtime().ipc().shms(namespace);
            return ToolOutcome.ok(ids.isEmpty() ? "no shm in namespace " + namespace
                    : "shm in " + namespace + ": " + String.join(", ", ids));
        }
        Optional<Shm> shm = ctx.runtime().ipc().findShm(namespace, name);
        if (shm.isEmpty()) {
            return ToolOutcome.error("shm \"" + name + "\" does not exist in namespace " + namespace);
        }
        return ToolOutcome.ok(String.join(", ", shm.get().cells()));
    }

    private ToolOutcome create(ToolContext ctx, String name) {
        requireName(name);
        Shm shm = ctx.runtime().ipc().attachShm(ctx.agent(), name);
        return ToolOutcome.ok("shm " + shm.namespaceId() + "/" + shm.id() + " ready");
    }

    private ToolOutcome read(ToolContext ctx, String name, String cell) {
        requireName(name);
        Optional<Shm> shm = ctx.runtime().ipc().findShm(ctx.agent().namespaceId(), name);
        if (shm.isEmpty()) {
            return ToolOutcome.error("shm \"" + name + "\" does not exist in namespace "
                    + ctx.agent().namespaceId());
        }
        Optional<Cell> value = shm.get().read(cell);
        if (value.isEmpty()) {
            return ToolOutcome.ok("cell " + name + "/" + cell + " is absent");
        }
        Cell found = value.get();
        return ToolOutcome.ok("cell " + name + "/" + cell + " v" + found.version() + ": "
                + json(found.value()));
    }

    private ToolOutcome write(ToolInvocation invocation, ToolContext ctx, String name) {
        requireName(name);
        String cell = requiredCell(invocation);
        requireValue(invocation);
        Shm shm = ctx.runtime().ipc().attachShm(ctx.agent(), name);
        Cell written = shm.write(cell, invocation.get("value"));
        return ToolOutcome.ok("wrote " + name + "/" + cell + " v" + written.version());
    }

    private ToolOutcome cas(ToolInvocation invocation, ToolContext ctx, String name) {
        requireName(name);
        String cell = requiredCell(invocation);
        requireValue(invocation);
        int expected = invocation.integer("expected_version", -1);
        if (expected < 0) {
            return ToolOutcome.error("cas requires a non-negative expected_version");
        }
        Shm shm = ctx.runtime().ipc().attachShm(ctx.agent(), name);
        Optional<Cell> written = shm.compareAndSet(cell, expected, invocation.get("value"));
        if (written.isEmpty()) {
            Optional<Cell> current = shm.read(cell);
            String version = current.map(value -> "v" + value.version()).orElse("absent");
            return ToolOutcome.error("cas conflict on " + name + "/" + cell + " (expected v"
                    + expected + ", current " + version + ")");
        }
        return ToolOutcome.ok("wrote " + name + "/" + cell + " v" + written.get().version());
    }

    private ToolOutcome delete(ToolContext ctx, String name, String cell) {
        requireName(name);
        Optional<Shm> shm = ctx.runtime().ipc().findShm(ctx.agent().namespaceId(), name);
        if (shm.isEmpty()) {
            return ToolOutcome.ok("cell " + name + "/" + cell + " was already absent");
        }
        boolean removed = shm.get().delete(cell);
        return ToolOutcome.ok(removed ? "deleted " + name + "/" + cell
                : "cell " + name + "/" + cell + " was already absent");
    }

    private ToolOutcome snapshot(ToolContext ctx, String name) {
        requireName(name);
        Optional<Shm> shm = ctx.runtime().ipc().findShm(ctx.agent().namespaceId(), name);
        if (shm.isEmpty()) {
            return ToolOutcome.error("shm \"" + name + "\" does not exist in namespace "
                    + ctx.agent().namespaceId());
        }
        Map<String, Object> cells = new LinkedHashMap<>();
        shm.get().snapshot().forEach((cell, value) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", value.version());
            entry.put("value", value.value());
            cells.put(cell, entry);
        });
        return ToolOutcome.ok(json(cells));
    }

    private static String requiredCell(ToolInvocation invocation) {
        String cell = invocation.string("cell", "").trim();
        if (cell.isBlank()) {
            throw new IllegalArgumentException("shm op requires a non-blank cell");
        }
        return cell;
    }

    private static void requireName(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("shm op requires a non-blank name");
        }
    }

    private static void requireValue(ToolInvocation invocation) {
        if (!invocation.arguments().containsKey("value")) {
            throw new IllegalArgumentException("shm write requires a value");
        }
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
