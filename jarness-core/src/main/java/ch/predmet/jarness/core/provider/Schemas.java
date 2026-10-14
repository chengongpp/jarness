package ch.predmet.jarness.core.provider;

import ch.predmet.jarness.api.session.ContentBlock;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.tool.ToolParameter;
import ch.predmet.jarness.api.tool.ToolSpec;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Shared rendering helpers: tool parameter JSON schemas and message block text extraction,
 * identical across the provider adapters.
 */
final class Schemas {

    private Schemas() {
    }

    /**
     * Render a {@link ToolSpec}'s parameters as a JSON schema object:
     * {@code {type:"object", properties:{<p>:{type, description}}, required:[...]}} with the
     * parameter declaration order preserved.
     */
    static ObjectNode parametersSchema(ToolSpec spec) {
        ObjectNode schema = HttpTransport.MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Map.Entry<String, ToolParameter> entry : spec.parameters().entrySet()) {
            ToolParameter parameter = entry.getValue();
            ObjectNode property = properties.putObject(entry.getKey());
            property.put("type", parameter.type());
            property.put("description", parameter.description());
            if (parameter.required()) {
                required.add(entry.getKey());
            }
        }
        return schema;
    }

    /** @return concatenated {@link ContentBlock.Text} text of a message. */
    static String textOf(Message message) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof ContentBlock.Text text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }
}
