package com.flowcanvas.engine;

import com.flowcanvas.graph.GraphNode;
import java.util.Map;

/**
 * The input handed to a node executor for one node.
 *
 * @param node      the node being executed.
 * @param inputs    the resolved outputs of the node's direct predecessors, keyed by node key.
 * @param variables the full map of upstream outputs, keyed by node key — this is what
 *                  {@code {{nodeKey}}} placeholders in a prompt template resolve against.
 */
public record NodeInput(
        GraphNode node,
        Map<String, String> inputs,
        Map<String, String> variables
) {
    /**
     * The value of {@code {{input}}}: the single incoming value, or — for a node that joins
     * several branches — every incoming value in key order, separated by a blank line.
     * Empty when the node has no input.
     */
    public String primaryInput() {
        return String.join("\n\n", inputs.values());
    }
}
