package com.flowcanvas.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire contract for execution resources. */
public final class ExecutionDtos {

    private ExecutionDtos() {
    }

    /** A workflow run. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecutionResponse(
            UUID id,
            UUID workflowId,
            String workflowName,
            String status,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            String error,
            List<NodeExecutionResponse> nodeExecutions
    ) {
        public ExecutionResponse {
            nodeExecutions = nodeExecutions == null ? List.of() : nodeExecutions;
        }
    }

    /** Per-node result within a run. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeExecutionResponse(
            UUID nodeId,
            String nodeKey,
            String label,
            String type,
            int sequence,
            String status,
            String output,
            String error,
            Long durationMs,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    /** Response to the execute endpoint: the run was accepted. */
    public record ExecutionAccepted(
            UUID executionId,
            UUID workflowId,
            String status
    ) {
    }

    /** Summary row in the run history. */
    public record ExecutionSummary(
            UUID id,
            String status,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            String error
    ) {
    }
}
