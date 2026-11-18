package com.flowcanvas.graph;

import com.flowcanvas.domain.NodeType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A node in the in-memory graph used by the validator and the execution engine.
 *
 * <p>Deliberately decoupled from the JPA entity: the engine works on plain immutable values so
 * it can be unit-tested without a database, and so that "what the engine sees" is trivially
 * comparable with "what the API sent".
 */
public record GraphNode(
        UUID id,
        String key,
        NodeType type,
        String label,
        double positionX,
        double positionY,
        Map<String, Object> config
) {
    public GraphNode {
        // LinkedHashMap + unmodifiableMap instead of Map.copyOf: the latter rejects null
        // values, and a JSONB config column legitimately contains them.
        config = config == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }

    public String configString(String name, String fallback) {
        Object value = config.get(name);
        return value == null ? fallback : String.valueOf(value);
    }

    public double configDouble(String name, double fallback) {
        Object value = config.get(name);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public Map<String, Object> mutableConfig() {
        return new LinkedHashMap<>(config);
    }
}
