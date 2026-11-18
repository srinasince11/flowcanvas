package com.flowcanvas.api;

import com.flowcanvas.api.dto.WorkflowDtos;
import com.flowcanvas.domain.NodeType;
import com.flowcanvas.graph.GraphEdge;
import com.flowcanvas.graph.GraphNode;
import com.flowcanvas.graph.WorkflowGraph;
import com.flowcanvas.graph.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Translates between the API payloads and the in-memory graph model. */
@Component
public class GraphMapper {

    public WorkflowGraph toGraph(List<WorkflowDtos.NodePayload> nodes,
                                 List<WorkflowDtos.EdgePayload> edges) {
        List<GraphNode> graphNodes = new ArrayList<>();
        for (WorkflowDtos.NodePayload payload : nodes) {
            graphNodes.add(new GraphNode(
                    payload.id(),
                    payload.key(),
                    NodeType.fromWire(payload.type()),
                    payload.label(),
                    payload.position().x(),
                    payload.position().y(),
                    payload.config()));
        }
        List<GraphEdge> graphEdges = new ArrayList<>();
        for (WorkflowDtos.EdgePayload payload : edges) {
            graphEdges.add(new GraphEdge(
                    payload.id(),
                    payload.source(),
                    payload.target(),
                    payload.sourceHandle(),
                    payload.targetHandle()));
        }
        return new WorkflowGraph(graphNodes, graphEdges);
    }

    public WorkflowDtos.NodePayload toPayload(GraphNode node) {
        return new WorkflowDtos.NodePayload(
                node.id(),
                node.key(),
                node.type().name(),
                node.label(),
                new WorkflowDtos.PositionPayload(node.positionX(), node.positionY()),
                node.mutableConfig());
    }

    public WorkflowDtos.EdgePayload toPayload(GraphEdge edge) {
        return new WorkflowDtos.EdgePayload(
                edge.id(), edge.sourceId(), edge.targetId(),
                edge.sourceHandle(), edge.targetHandle());
    }

    public WorkflowDtos.IssuePayload toPayload(ValidationIssue issue) {
        return new WorkflowDtos.IssuePayload(
                issue.severity().name(),
                issue.code(),
                issue.message(),
                issue.nodeId(),
                issue.edgeId());
    }

    /** Builds the graph from persisted entities. */
    public WorkflowGraph toGraphFromEntities(List<com.flowcanvas.domain.WorkflowNode> nodes,
                                             List<com.flowcanvas.domain.WorkflowEdge> edges) {
        List<GraphNode> graphNodes = new ArrayList<>();
        for (com.flowcanvas.domain.WorkflowNode node : nodes) {
            graphNodes.add(new GraphNode(
                    node.getId(),
                    node.getNodeKey(),
                    node.nodeType(),
                    node.getLabel(),
                    node.getPositionX(),
                    node.getPositionY(),
                    node.getConfig()));
        }
        List<GraphEdge> graphEdges = new ArrayList<>();
        for (com.flowcanvas.domain.WorkflowEdge edge : edges) {
            graphEdges.add(new GraphEdge(
                    edge.getId(),
                    edge.getSourceId(),
                    edge.getTargetId(),
                    edge.getSourceHandle(),
                    edge.getTargetHandle()));
        }
        return new WorkflowGraph(graphNodes, graphEdges);
    }

    public Map<String, Object> emptyConfig() {
        return Map.of();
    }
}
