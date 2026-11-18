package com.flowcanvas.engine;

import com.flowcanvas.domain.NodeExecutionStatus;
import com.flowcanvas.domain.NodeType;
import java.time.Instant;
import java.util.UUID;

/**
 * The outcome of executing one node.
 *
 * @param nodeId     the node.
 * @param nodeKey    the node key, used for template variables.
 * @param label      the node label.
 * @param type       the node type.
 * @param sequence   1-based position in the resolved execution order.
 * @param status     the terminal status.
 * @param output     the produced text, {@code null} when the node failed or was skipped.
 * @param error      the failure message, {@code null} on success.
 * @param durationMs wall-clock duration of the node.
 * @param startedAt  when the node started (or was skipped).
 * @param finishedAt when the node reached its terminal state.
 */
public record NodeExecutionResult(
        UUID nodeId,
        String nodeKey,
        String label,
        NodeType type,
        int sequence,
        NodeExecutionStatus status,
        String output,
        String error,
        long durationMs,
        Instant startedAt,
        Instant finishedAt
) {
}
