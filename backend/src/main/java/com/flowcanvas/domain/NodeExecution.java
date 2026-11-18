package com.flowcanvas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The per-node record of a workflow run: what ran, in which order, how long it took and what
 * it produced. {@code sequence} is the 1-based position in the resolved execution order, which
 * makes the dependency ordering directly observable in the database and in the API.
 */
@Entity
@Table(name = "node_executions")
public class NodeExecution {

    @Id
    private UUID id;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "node_key", nullable = false, length = 64)
    private String nodeKey;

    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "status", nullable = false, length = 20)
    private String status = NodeExecutionStatus.PENDING.name();

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "output", columnDefinition = "text")
    private String output;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected NodeExecution() {
        // for JPA
    }

    public NodeExecution(UUID id, UUID executionId, UUID nodeId, String nodeKey, NodeType nodeType,
                         String label, int sequence) {
        this.id = id;
        this.executionId = executionId;
        this.nodeId = nodeId;
        this.nodeKey = nodeKey;
        this.nodeType = nodeType.name();
        this.label = label;
        this.sequence = sequence;
        this.status = NodeExecutionStatus.PENDING.name();
        this.startedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public String getNodeType() {
        return nodeType;
    }

    public String getLabel() {
        return label;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getSequence() {
        return sequence;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
}
