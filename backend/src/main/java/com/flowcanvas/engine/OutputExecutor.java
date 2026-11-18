package com.flowcanvas.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcanvas.domain.NodeType;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Terminal sink. Collects the values of its incoming connections and emits them as the
 * workflow's result.
 *
 * <p>Config: {@code {"format": "text" | "json"}}. In {@code json} mode the collected values
 * are emitted as a JSON object keyed by the upstream node keys, which keeps the output
 * machine-readable. Serialisation goes through Jackson rather than string concatenation, so
 * every value — control characters and unicode included — is escaped correctly.
 */
@Component
public class OutputExecutor implements NodeExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public NodeType type() {
        return NodeType.OUTPUT;
    }

    @Override
    public String execute(NodeInput input) {
        String format = input.node().configString("format", "text").toLowerCase(Locale.ROOT);
        if ("json".equals(format)) {
            try {
                return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(input.inputs());
            } catch (JsonProcessingException exception) {
                throw new NodeExecutionException("Could not serialise the output as JSON.", exception);
            }
        }
        return String.join("\n", input.inputs().values());
    }
}
