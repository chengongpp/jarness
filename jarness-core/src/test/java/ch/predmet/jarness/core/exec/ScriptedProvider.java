package ch.predmet.jarness.core.exec;

import ch.predmet.jarness.api.config.ConfigView;
import ch.predmet.jarness.api.provider.ChatRequest;
import ch.predmet.jarness.api.provider.ChatResponse;
import ch.predmet.jarness.api.provider.ModelInfo;
import ch.predmet.jarness.api.provider.Provider;
import ch.predmet.jarness.api.provider.ProviderKind;
import ch.predmet.jarness.api.provider.ProviderProfile;
import ch.predmet.jarness.api.provider.StreamSink;
import ch.predmet.jarness.api.provider.Usage;
import ch.predmet.jarness.api.session.ContentBlock;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Role;

import java.util.List;
import java.util.Map;

/** A deterministic model: asks for one {@code write}, then answers {@code done}. */
public final class ScriptedProvider implements Provider {

    public static final String ID = "scripted";
    public static final String MODEL = "scripted-model";

    private final ProviderProfile profile = new ProviderProfile(ID, "Scripted", ProviderKind.OPENAI_COMPAT,
            "http://localhost", "", Map.of(), List.of(ModelInfo.of(MODEL, MODEL, 4096, 1024)),
            Map.of(), ConfigView.empty());
    private final String tool;
    private final String arguments;
    private int calls;

    public ScriptedProvider() {
        this("write", "{\"file_path\":\"relay.txt\",\"content\":\"from the executor\"}");
    }

    public ScriptedProvider(String tool, String arguments) {
        this.tool = tool;
        this.arguments = arguments;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.OPENAI_COMPAT;
    }

    @Override
    public ProviderProfile profile() {
        return profile;
    }

    @Override
    public List<ModelInfo> models() {
        return profile.models();
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        return complete(request, StreamSink.DISCARD);
    }

    @Override
    public ChatResponse complete(ChatRequest request, StreamSink sink) {
        calls++;
        if (calls == 1) {
            ContentBlock.ToolCall call = new ContentBlock.ToolCall("call-1", tool, arguments);
            return new ChatResponse("r1", new Message(null, Role.ASSISTANT, List.of(call), null),
                    new Usage(11, 5, 0), "tool_calls", "");
        }
        return new ChatResponse("r2", new Message(null, Role.ASSISTANT,
                List.of(new ContentBlock.Text("done")), null), new Usage(12, 1, 0), "stop", "");
    }
}
