package com.flowcanvas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A directed connection between two {@link WorkflowNode}s. */
@Entity
@Table(name = "workflow_edges")
public class WorkflowEdge {

    @Id
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "source_handle", length = 64)
    private String sourceHandle;

    @Column(name = "target_handle", length = 64)
    private String targetHandle;

    protected WorkflowEdge() {
        // for JPA
    }

    public WorkflowEdge(UUID id, UUID workflowId, UUID sourceId, UUID targetId,
                        String sourceHandle, String targetHandle) {
        this.id = id;
        this.workflowId = workflowId;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.sourceHandle = sourceHandle;
        this.targetHandle = targetHandle;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getSourceHandle() {
        return sourceHandle;
    }

    public void setSourceHandle(String sourceHandle) {
        this.sourceHandle = sourceHandle;
    }

    public String getTargetHandle() {
        return targetHandle;
    }

    public void setTargetHandle(String targetHandle) {
        this.targetHandle = targetHandle;
    }
}
