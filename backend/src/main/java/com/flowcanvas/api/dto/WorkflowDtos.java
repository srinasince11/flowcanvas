package com.flowcanvas.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire contract for a workflow graph.
 *
 * <p>All request and response bodies for the workflow resource are in this file so the API
 * surface can be reviewed in one place. Field names match the React Flow node/edge shape
 * closely enough that the frontend adapter is a thin mapping rather than a translation layer.
 */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    /** A node as sent by and returned to the client. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodePayload(
            @NotNull UUID id,
            @NotBlank @Size(max = 64) String key,
            @NotBlank String type,
            @NotBlank @Size(max = 120) String label,
            @NotNull PositionPayload position,
            Map<String, Object> config
    ) {
        public NodePayload {
            config = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
        }
    }

    /** Canvas coordinates in pixels. */
    public record PositionPayload(@NotNull Double x, @NotNull Double y) {
    }

    /** An edge as sent by and returned to the client. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EdgePayload(
            @NotNull UUID id,
            @NotNull UUID source,
            @NotNull UUID target,
            @Size(max = 64) String sourceHandle,
            @Size(max = 64) String targetHandle
    ) {
    }

    /** Create/update body for a workflow. */
    public record WorkflowRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @Size(max = 20) String status,
            @Valid List<NodePayload> nodes,
            @Valid List<EdgePayload> edges
    ) {
        public WorkflowRequest {
            nodes = nodes == null ? List.of() : nodes;
            edges = edges == null ? List.of() : edges;
        }
    }

    /** Full workflow detail, including the graph. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkflowResponse(
            UUID id,
            String name,
            String description,
            String status,
            List<NodePayload> nodes,
            List<EdgePayload> edges,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
    ) {
    }

    /** Lightweight entry for the workflow library list. */
    public record WorkflowSummary(
            UUID id,
            String name,
            String description,
            String status,
            int nodeCount,
            int edgeCount,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
    ) {
    }

    /** Paged list wrapper so the frontend never has to guess about pagination. */
    public record PageResponse<T>(
            List<T> items,
            int page,
            int size,
            long totalItems,
            int totalPages
    ) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }

        public static <T> PageResponse<T> of(List<T> items) {
            return new PageResponse<>(items, 0, items.size(), items.size(), items.isEmpty() ? 0 : 1);
        }
    }

    /** Request body for the validate endpoint. */
    public record ValidateRequest(
            @Valid List<NodePayload> nodes,
            @Valid List<EdgePayload> edges
    ) {
        public ValidateRequest {
            nodes = nodes == null ? new ArrayList<>() : nodes;
            edges = edges == null ? new ArrayList<>() : edges;
        }
    }

    /** Validation outcome for a graph. */
    public record ValidationResponse(
            boolean valid,
            List<IssuePayload> issues,
            List<ExecutionOrderEntry> executionOrder
    ) {
        public ValidationResponse {
            issues = issues == null ? List.of() : issues;
            executionOrder = executionOrder == null ? List.of() : executionOrder;
        }
    }

    /** A single validation problem. */
    public record IssuePayload(
            String severity,
            String code,
            String message,
            UUID nodeId,
            UUID edgeId
    ) {
    }

    /** One position in the resolved execution order. */
    public record ExecutionOrderEntry(
            int sequence,
            UUID nodeId,
            String nodeKey,
            String label,
            String type
    ) {
    }
}
