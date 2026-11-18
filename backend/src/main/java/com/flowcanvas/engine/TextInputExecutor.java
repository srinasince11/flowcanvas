package com.flowcanvas.engine;

import com.flowcanvas.domain.NodeType;
import org.springframework.stereotype.Component;

/**
 * Emits a literal text value. The entry point of every workflow.
 *
 * <p>Config: {@code {"text": "..."}}.
 */
@Component
public class TextInputExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.TEXT_INPUT;
    }

    @Override
    public String execute(NodeInput input) {
        return input.node().configString("text", "");
    }
}
