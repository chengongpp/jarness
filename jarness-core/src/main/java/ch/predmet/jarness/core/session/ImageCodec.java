package ch.predmet.jarness.core.session;

import ch.predmet.jarness.api.capability.CapKind;
import ch.predmet.jarness.api.capability.Capability;
import ch.predmet.jarness.api.capability.CapabilitySet;
import ch.predmet.jarness.api.capability.Constraints;
import ch.predmet.jarness.api.kernel.Budget;
import ch.predmet.jarness.api.session.Message;
import ch.predmet.jarness.api.session.Session;
import ch.predmet.jarness.api.session.Step;
import ch.predmet.jarness.api.session.Turn;
import ch.predmet.jarness.api.session.TurnState;
import ch.predmet.jarness.api.session.ImageMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON codec for the image columns of the local store.
 *
 * <p>Messages use the same polymorphic content-block format as the session store. Budgets,
 * capabilities, and turns are hand-mapped: they are value objects whose JSON shape should stay
 * readable and stable, not reflection-shaped.</p>
 */
public final class ImageCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final TypeReference<List<Message>> MESSAGES = new TypeReference<>() {
    };

    private ImageCodec() {
    }

    /** A committed turn plus the messages it produced. */
    public record TurnDelta(TurnImpl turn, List<Message> messages) {
        public TurnDelta {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    /** A checkpoint snapshot: the full context at one turn boundary. */
    public record Snapshot(List<Message> messages, List<TurnImpl> turns) {
        public Snapshot {
            messages = messages == null ? List.of() : List.copyOf(messages);
            turns = turns == null ? List.of() : List.copyOf(turns);
        }
    }

    /**
     * @param metadata image metadata
     * @return JSON
     */
    public static String writeImage(ImageMetadata metadata) {
        try {
            return MAPPER.writeValueAsString(imageMap(metadata));
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode image metadata", e);
        }
    }

    /**
     * @param json JSON, or {@code null}
     * @return the metadata, or {@code null} when absent/unreadable
     */
    public static ImageMetadata readImage(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {
            });
            return imageFrom(map);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param turn     the terminal turn
     * @param messages messages produced during it
     * @return JSON
     */
    public static String writeTurn(Turn turn, List<Message> messages) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("turn", turnMap(turn));
        root.put("messages", messages == null ? List.of() : messages);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode turn " + turn.id(), e);
        }
    }

    /**
     * @param sessionId owning session
     * @param json      JSON
     * @return the decoded delta, or {@code null} when absent/unreadable
     */
    public static TurnDelta readTurn(String sessionId, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> turnMap = (Map<String, Object>) map.get("turn");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawMessages = (List<Map<String, Object>>) map.getOrDefault("messages",
                    List.of());
            List<Message> messages = new ArrayList<>(rawMessages.size());
            for (Map<String, Object> raw : rawMessages) {
                messages.add(MAPPER.convertValue(raw, Message.class));
            }
            return new TurnDelta(turnFrom(sessionId, turnMap), messages);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param session the session
     * @return JSON snapshot
     */
    public static String writeSnapshot(Session session) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("messages", session.messages());
        List<Map<String, Object>> turns = new ArrayList<>();
        for (Turn turn : session.turns()) {
            turns.add(turnMap(turn));
        }
        root.put("turns", turns);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode checkpoint of " + session.id(), e);
        }
    }

    /**
     * @param sessionId owning session
     * @param json      JSON
     * @return the snapshot, or {@code null} when unreadable
     */
    public static Snapshot readSnapshot(String sessionId, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {
            });
            List<Message> messages = MAPPER.convertValue(map.getOrDefault("messages", List.of()), MESSAGES);
            List<TurnImpl> turns = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawTurns = (List<Map<String, Object>>) map.getOrDefault("turns", List.of());
            for (Map<String, Object> raw : rawTurns) {
                turns.add(turnFrom(sessionId, raw));
            }
            return new Snapshot(messages, turns);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param caps capability set
     * @return the capability array as JSON
     */
    public static String capsJson(CapabilitySet caps) {
        try {
            return MAPPER.writeValueAsString(capsMaps(caps));
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode capabilities", e);
        }
    }

    /**
     * @param json capability array JSON
     * @return the decoded set, empty when unreadable
     */
    @SuppressWarnings("unchecked")
    public static CapabilitySet capsFromJson(String json) {
        if (json == null || json.isBlank()) {
            return CapabilitySet.empty();
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(json, new TypeReference<>() {
            });
            return capsFrom(raw);
        } catch (Exception e) {
            return CapabilitySet.empty();
        }
    }

    // ------------------------------------------------------------- maps

    private static Map<String, Object> imageMap(ImageMetadata metadata) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("agentId", metadata.agentId());
        root.put("sessionId", metadata.sessionId());
        root.put("namespace", metadata.namespace());
        root.put("schemaVersion", metadata.schemaVersion());
        root.put("checkpointTurn", metadata.checkpointTurn());
        root.put("deps", metadata.deps());
        root.put("budget", budgetMap(metadata.budget()));
        root.put("caps", capsMaps(metadata.capabilities()));
        root.put("state", metadata.state());
        root.put("updatedAt", metadata.updatedAt().toString());
        return root;
    }

    @SuppressWarnings("unchecked")
    private static ImageMetadata imageFrom(Map<String, Object> map) {
        Map<String, Object> deps = (Map<String, Object>) map.getOrDefault("deps", Map.of());
        Budget budget = budgetFrom((Map<String, Object>) map.get("budget"));
        CapabilitySet caps = capsFrom((List<Map<String, Object>>) map.get("caps"));
        return new ImageMetadata(
                string(map.get("agentId")),
                string(map.get("sessionId")),
                string(map.get("namespace")),
                integer(map.get("schemaVersion"), ImageMetadata.SCHEMA_VERSION),
                integer(map.get("checkpointTurn"), -1),
                deps,
                budget,
                caps,
                string(map.get("state")),
                instant(map.get("updatedAt")));
    }

    private static Map<String, Object> budgetMap(Budget budget) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tokensLeft", budget.tokensLeft());
        map.put("deadline", budget.deadline() == null ? null : budget.deadline().toString());
        map.put("toolSlots", budget.toolSlots());
        map.put("maxSpawnDepth", budget.maxSpawnDepth());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Budget budgetFrom(Map<String, Object> map) {
        if (map == null) {
            return Budget.unlimited();
        }
        return new Budget(
                longValue(map.get("tokensLeft"), -1L),
                instant(map.get("deadline")),
                integer(map.get("toolSlots"), -1),
                integer(map.get("maxSpawnDepth"), -1));
    }

    private static List<Map<String, Object>> capsMaps(CapabilitySet caps) {
        List<Map<String, Object>> out = new ArrayList<>(caps.size());
        for (Capability cap : caps.capabilities()) {
            out.add(capMap(cap));
        }
        return out;
    }

    private static Map<String, Object> capMap(Capability cap) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("capId", cap.capId());
        map.put("kind", cap.kind().name());
        map.put("resource", cap.resource());
        Constraints limits = cap.limits();
        Map<String, Object> lim = new LinkedHashMap<>();
        lim.put("scope", limits.scope());
        lim.put("maxTokens", limits.maxTokens());
        lim.put("deadline", limits.deadline() == null ? null : limits.deadline().toString());
        lim.put("maxSpawnDepth", limits.maxSpawnDepth());
        map.put("limits", lim);
        return map;
    }

    private static CapabilitySet capsFrom(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return CapabilitySet.empty();
        }
        List<Capability> caps = new ArrayList<>(raw.size());
        for (Map<String, Object> map : raw) {
            CapKind kind = CapKind.valueOf(string(map.get("kind")));
            @SuppressWarnings("unchecked")
            Map<String, Object> lim = (Map<String, Object>) map.getOrDefault("limits", Map.of());
            Constraints limits = new Constraints(
                    string(lim.get("scope")),
                    longValue(lim.get("maxTokens"), -1L),
                    instant(lim.get("deadline")),
                    integer(lim.get("maxSpawnDepth"), -1));
            caps.add(new Capability(string(map.get("capId")), kind, string(map.get("resource")), limits));
        }
        return CapabilitySet.of(caps);
    }

    private static Map<String, Object> turnMap(Turn turn) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", turn.id());
        map.put("index", turn.index());
        map.put("state", turn.state().name());
        map.put("input", turn.input());
        map.put("output", turn.output());
        map.put("error", turn.error());
        map.put("startedAt", turn.startedAt().toString());
        map.put("endedAt", turn.endedAt() == null ? null : turn.endedAt().toString());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step step : turn.steps()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("index", step.index());
            s.put("startedAt", step.startedAt().toString());
            s.put("endedAt", step.endedAt() == null ? null : step.endedAt().toString());
            s.put("finishReason", step.finishReason());
            s.put("promptTokens", step.promptTokens());
            s.put("completionTokens", step.completionTokens());
            s.put("toolCalls", step.toolCalls());
            steps.add(s);
        }
        map.put("steps", steps);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static TurnImpl turnFrom(String sessionId, Map<String, Object> map) {
        List<Map<String, Object>> rawSteps = (List<Map<String, Object>>) map.getOrDefault("steps", List.of());
        List<Step> steps = new ArrayList<>(rawSteps.size());
        for (Map<String, Object> raw : rawSteps) {
            steps.add(new Step(
                    integer(raw.get("index"), 0),
                    instant(raw.get("startedAt")),
                    instant(raw.get("endedAt")),
                    string(raw.get("finishReason")),
                    integer(raw.get("promptTokens"), 0),
                    integer(raw.get("completionTokens"), 0),
                    integer(raw.get("toolCalls"), 0)));
        }
        return TurnImpl.restore(
                string(map.get("id")),
                sessionId,
                integer(map.get("index"), 0),
                TurnState.valueOf(string(map.get("state"))),
                string(map.get("input")),
                string(map.get("output")),
                string(map.get("error")),
                instant(map.get("startedAt")),
                instant(map.get("endedAt")),
                steps);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Instant instant(Object value) {
        if (value == null || String.valueOf(value).isBlank() || String.valueOf(value).equals("null")) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}
