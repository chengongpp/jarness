package ch.predmet.jarness.core.session;

import ch.predmet.jarness.api.session.ContentBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson codec for the JSON columns of the session store: message content blocks and session
 * metadata.
 *
 * <p>{@link ContentBlock} already carries {@code @JsonTypeInfo}/{@code @JsonSubTypes}, so a plain
 * {@link ObjectMapper} round-trips polymorphic block lists without extra configuration. All read
 * methods are lenient: {@code null} or blank input decodes to an empty list/map, and malformed JSON
 * decodes to empty after logging a warning, so one corrupt row does not kill a resume.</p>
 */
public final class MessageCodec {

    private static final Logger LOG = LoggerFactory.getLogger(MessageCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ContentBlock>> BLOCKS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() {
    };

    private MessageCodec() {
    }

    /**
     * Serialize content blocks to JSON.
     *
     * @param blocks the blocks, or {@code null}
     * @return the JSON array, {@code "[]"} for null/empty input
     */
    public static String writeBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writerFor(BLOCKS).writeValueAsString(blocks);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize content blocks", e);
        }
    }

    /**
     * Deserialize content blocks.
     *
     * @param json the JSON array, or {@code null}/blank
     * @return the blocks, empty on null/blank/malformed input
     */
    public static List<ContentBlock> readBlocks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ContentBlock> blocks = MAPPER.readValue(json, BLOCKS);
            return blocks == null ? List.of() : blocks;
        } catch (Exception e) {
            LOG.warn("undecodable content blocks, returning empty list: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Serialize session metadata to JSON.
     *
     * @param metadata the metadata, or {@code null}
     * @return the JSON object, {@code "{}"} for null/empty input
     */
    public static String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize session metadata", e);
        }
    }

    /**
     * Deserialize session metadata.
     *
     * @param json the JSON object, or {@code null}/blank
     * @return the metadata, empty on null/blank/malformed input
     */
    public static Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> metadata = MAPPER.readValue(json, METADATA);
            return metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
        } catch (Exception e) {
            LOG.warn("undecodable session metadata, returning empty map: {}", e.toString());
            return Map.of();
        }
    }
}
