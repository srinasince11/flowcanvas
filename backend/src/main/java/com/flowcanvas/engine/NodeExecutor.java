package com.flowcanvas.engine;

import com.flowcanvas.domain.NodeType;

/**
 * Executes one kind of node.
 *
 * <p>Implementations are stateless and side-effect free apart from the AI call, which makes
 * each node type independently unit testable.
 */
public interface NodeExecutor {

    /** The node type this executor handles. */
    NodeType type();

    /**
     * Runs the node.
     *
     * @return the node's output text, which becomes the input of its successors.
     * @throws NodeExecutionException when the node fails; the engine records the failure and
     *                               marks descendants as skipped.
     */
    String execute(NodeInput input);
}
