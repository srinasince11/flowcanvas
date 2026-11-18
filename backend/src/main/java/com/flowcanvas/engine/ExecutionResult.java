package com.flowcanvas.engine;

import com.flowcanvas.domain.ExecutionStatus;
import com.flowcanvas.graph.ValidationIssue;
import java.util.List;

/**
 * The outcome of a whole workflow run.
 *
 * @param status   terminal run status.
 * @param results  per-node results in execution order.
 * @param issues   validation issues; when non-empty the run did not start.
 * @param error    the run-level error message, if any.
 * @param durationMs total wall-clock duration.
 */
public record ExecutionResult(
        ExecutionStatus status,
        List<NodeExecutionResult> results,
        List<ValidationIssue> issues,
        String error,
        long durationMs
) {
    public ExecutionResult {
        results = results == null ? List.of() : List.copyOf(results);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean succeeded() {
        return status == ExecutionStatus.SUCCESS;
    }
}
