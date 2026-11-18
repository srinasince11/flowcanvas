package com.flowcanvas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single node in a workflow graph.
 *
 * <p>{@code config} is a JSONB column because its shape is owned by the node type and evolves
 * independently of the relational schema — adding a field to a prompt template must never
 * require a database migration.
 */
@Entity
@Table(name = "workflow_nodes")
public class WorkflowNode {

    @Id
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "node_key", nullable = false, length = 64)
    private String nodeKey;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();

    protected WorkflowNode() {
        // for JPA
    }

    public WorkflowNode(UUID id, UUID workflowId, String nodeKey, NodeType type, String label,
                        double positionX, double positionY, java.util.Map<String, Object> config) {
        this.id = id;
        this.workflowId = workflowId;
        this.nodeKey = nodeKey;
        this.type = type.name();
        this.label = label;
        this.positionX = positionX;
        this.positionY = positionY;
        this.config = config == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(config);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public void setNodeKey(String nodeKey) {
        this.nodeKey = nodeKey;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public NodeType nodeType() {
        return NodeType.valueOf(type);
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public double getPositionX() {
        return positionX;
    }

    public void setPositionX(double positionX) {
        this.positionX = positionX;
    }

    public double getPositionY() {
        return positionY;
    }

    public void setPositionY(double positionY) {
        this.positionY = positionY;
    }

    public java.util.Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(java.util.Map<String, Object> config) {
        this.config = config == null ? new java.util.LinkedHashMap<>() : config;
    }
}
