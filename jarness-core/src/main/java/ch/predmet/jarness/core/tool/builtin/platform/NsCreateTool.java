package ch.predmet.jarness.core.tool.builtin.platform;

import ch.predmet.jarness.api.namespace.ModelProfile;
import ch.predmet.jarness.api.namespace.Namespace;
import ch.predmet.jarness.api.namespace.NamespaceMetadata;
import ch.predmet.jarness.api.namespace.NamespaceSpec;
import ch.predmet.jarness.api.provider.ModelRef;
import ch.predmet.jarness.api.tool.Tool;
import ch.predmet.jarness.api.tool.ToolContext;
import ch.predmet.jarness.api.tool.ToolInvocation;
import ch.predmet.jarness.api.tool.ToolOutcome;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Platform tool: create a namespace inheriting the default capability ceiling and store. */
public final class NsCreateTool implements Tool {

    private final ToolSpec spec = ToolSpec.mutating("ns_create",
            "Create a namespace. It inherits the default namespace's store and capability ceiling.",
            params());

    private static Map<String, ToolParameter> params() {
        Map<String, ToolParameter> params = new LinkedHashMap<>();
        params.put("id", ToolParameter.required("string", "Namespace id."));
        params.put("template", ToolParameter.optional("string", "Default template id; defaults to the default namespace's."));
        params.put("model", ToolParameter.optional("string", "Model selector, provider/model or bare model id."));
        params.put("cwd", ToolParameter.optional("string",
                "Shared working directory. Defaults to <base>/workspaces/<id>."));
        return params;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation, ToolContext ctx) {
        String id = invocation.requireString("id");
        Namespace base = ctx.runtime().namespaces().defaultNs();
        String template = invocation.string("template", base.defaultTemplateId());
        ModelProfile model = invocation.findString("model")
                .map(selector -> ModelProfile.of(ModelRef.parse(selector)))
                .orElse(base.metadata().model());
        Path cwd = invocation.findString("cwd")
                .map(Path::of)
                .map(path -> path.isAbsolute() ? path : ctx.runtime().layout().baseDir().resolve(path))
                .orElseGet(() -> ctx.runtime().layout().workspacesDir().resolve(id))
                .toAbsolutePath().normalize();
        NamespaceMetadata metadata = new NamespaceMetadata(
                id, cwd, Map.of(), model, Map.of(), base.metadata().policies(), base.metadata().defaults());
        Namespace created = ctx.runtime().namespaces().create(new NamespaceSpec(
                id, metadata, base.store(), template, "", base.executionProviders()));
        return ToolOutcome.ok("created namespace " + created.id() + " (template " + template + ")");
    }
}
