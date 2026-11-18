package com.flowcanvas.engine;

import com.flowcanvas.graph.GraphNode;
import java.time.Instant;
import java.util.List;

/**
 * Observes a run as it happens.
 *
 * <p>The engine stays free of persistence and HTTP concerns; whoever drives it (the
 * {@code ExecutionRunner} in production, a test in isolation) decides what to do with each
 * event. That is what lets the database — and therefore the polling UI — show a run's
 * progress node by node instead of only after the last node has finished.
 *
 * <p>Callbacks are invoked synchronously on the executing thread, in order. A listener that
 * throws aborts the run, so implementations should not swallow persistence failures silently.
 */
public interface ExecutionListener {

    /** A listener that ignores every event. */
    ExecutionListener NONE = new ExecutionListener() {
    };

    /** The validated graph has been ordered; {@code ordered} is the full execution plan. */
    default void onPlanned(List<GraphNode> ordered) {
    }

    /** A node is about to run. */
    default void onNodeStarted(GraphNode node, int sequence, Instant startedAt) {
    }

    /** A node reached a terminal state: succeeded, failed or skipped. */
    default void onNodeFinished(NodeExecutionResult result) {
    }
}
