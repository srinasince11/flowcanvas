package com.flowcanvas.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable workflow graph: nodes indexed by id, plus the edge list.
 *
 * <p>Adjacency lookups are pre-computed in the constructor so that the validator and the
 * topological sorter never re-scan the edge list in a loop — the classic O(V*E) trap.
 */
public final class WorkflowGraph {

    private final Map<UUID, GraphNode> nodes;
    private final List<GraphEdge> edges;
    private final Map<UUID, List<GraphEdge>> outgoing;
    private final Map<UUID, List<GraphEdge>> incoming;

    public WorkflowGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
        Map<UUID, GraphNode> byId = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            byId.put(node.id(), node);
        }
        // NOTE: Map.copyOf() does NOT preserve insertion order. Using it here would make
        // node iteration order (and therefore topological tie-breaking) non-deterministic.
        this.nodes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        this.edges = List.copyOf(edges);

        Map<UUID, List<GraphEdge>> out = new LinkedHashMap<>();
        Map<UUID, List<GraphEdge>> in = new LinkedHashMap<>();
        for (GraphNode node : this.nodes.values()) {
            out.put(node.id(), new ArrayList<>());
            in.put(node.id(), new ArrayList<>());
        }
        for (GraphEdge edge : this.edges) {
            out.computeIfAbsent(edge.sourceId(), k -> new ArrayList<>()).add(edge);
            in.computeIfAbsent(edge.targetId(), k -> new ArrayList<>()).add(edge);
        }
        this.outgoing = Map.copyOf(out);
        this.incoming = Map.copyOf(in);
    }

    public Map<UUID, GraphNode> nodes() {
        return nodes;
    }

    public List<GraphNode> nodeList() {
        return List.copyOf(nodes.values());
    }

    public List<GraphEdge> edges() {
        return edges;
    }

    public GraphNode node(UUID id) {
        return nodes.get(id);
    }

    public List<GraphEdge> outgoing(UUID nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    public List<GraphEdge> incoming(UUID nodeId) {
        return incoming.getOrDefault(nodeId, List.of());
    }

    public int inDegree(UUID nodeId) {
        return incoming(nodeId).size();
    }

    public int outDegree(UUID nodeId) {
        return outgoing(nodeId).size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /**
     * Every node from which {@code nodeId} is reachable, i.e. its transitive producers.
     *
     * <p>Iterative with a visited set, so it terminates on cyclic input and a deep graph
     * cannot overflow the call stack.
     */
    public Set<UUID> ancestors(UUID nodeId) {
        Set<UUID> visited = new LinkedHashSet<>();
        Deque<UUID> stack = new ArrayDeque<>();
        for (GraphEdge edge : incoming(nodeId)) {
            stack.push(edge.sourceId());
        }
        while (!stack.isEmpty()) {
            UUID current = stack.pop();
            if (!nodes.containsKey(current) || !visited.add(current)) {
                continue;
            }
            for (GraphEdge edge : incoming(current)) {
                stack.push(edge.sourceId());
            }
        }
        return visited;
    }
}
