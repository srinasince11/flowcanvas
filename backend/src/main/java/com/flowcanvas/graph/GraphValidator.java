package com.flowcanvas.graph;

import com.flowcanvas.domain.NodeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Validates a workflow graph before it is allowed to run.
 *
 * <p>Two classes of check live here:
 * <ol>
 *   <li><b>Structural checks</b> — dangling edges, self loops, duplicate edges, and the
 *       per-node-type arity rules declared on {@link NodeType}.</li>
 *   <li><b>Cyclomatic checks</b> — a depth-first search with a recursion-stack colour map
 *       detects back edges and reports the exact cycle, not just "there is a cycle".</li>
 * </ol>
 *
 * <p>The validator is pure: it takes a graph and returns issues. It has no Spring state
 * beyond being a bean, which is what lets it be unit tested in isolation and reused by the
 * "validate before save" endpoint.
 */
@Component
public class GraphValidator {

    /** Detects cycles and reports the offending node ids in cycle order. */
    public CycleResult detectCycles(WorkflowGraph graph) {
        Set<UUID> visited = new HashSet<>();
        Set<UUID> onStack = new LinkedHashSet<>();
        Deque<UUID> path = new ArrayDeque<>();
        List<UUID> cycle = new ArrayList<>();

        for (GraphNode node : graph.nodeList()) {
            if (!visited.contains(node.id())) {
                if (findCycle(graph, node.id(), visited, onStack, path, cycle)) {
                    return new CycleResult(true, List.copyOf(cycle));
                }
            }
        }
        return new CycleResult(false, List.of());
    }

    private boolean findCycle(WorkflowGraph graph, UUID nodeId, Set<UUID> visited, Set<UUID> onStack,
                              Deque<UUID> path, List<UUID> cycle) {
        visited.add(nodeId);
        onStack.add(nodeId);
        path.push(nodeId);

        for (GraphEdge edge : graph.outgoing(nodeId)) {
            if (onStack.contains(edge.targetId())) {
                // Found a back edge. `path` is a stack (most recent first), so walk it from
                // the bottom up and keep the segment that starts at the back edge's target:
                // that yields the cycle in edge direction, closed on its first node.
                List<UUID> reconstructed = new ArrayList<>();
                Iterator<UUID> bottomUp = path.descendingIterator();
                boolean inCycle = false;
                while (bottomUp.hasNext()) {
                    UUID candidate = bottomUp.next();
                    inCycle = inCycle || candidate.equals(edge.targetId());
                    if (inCycle) {
                        reconstructed.add(candidate);
                    }
                }
                reconstructed.add(edge.targetId());
                cycle.addAll(reconstructed);
                return true;
            }
            if (!visited.contains(edge.targetId())) {
                if (findCycle(graph, edge.targetId(), visited, onStack, path, cycle)) {
                    return true;
                }
            }
        }

        path.pop();
        onStack.remove(nodeId);
        return false;
    }

    /** Runs every validation rule and returns all issues, errors first. */
    public List<ValidationIssue> validate(WorkflowGraph graph) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (graph.isEmpty()) {
            issues.add(ValidationIssue.error("GRAPH_EMPTY", "Workflow contains no nodes."));
            return issues;
        }

        // --- Edges reference existing nodes -------------------------------------------
        for (GraphEdge edge : graph.edges()) {
            if (graph.node(edge.sourceId()) == null) {
                issues.add(ValidationIssue.error("EDGE_SOURCE_MISSING",
                        "Edge references a source node that does not exist.", null, edge.id()));
            }
            if (graph.node(edge.targetId()) == null) {
                issues.add(ValidationIssue.error("EDGE_TARGET_MISSING",
                        "Edge references a target node that does not exist.", null, edge.id()));
            }
            if (edge.sourceId().equals(edge.targetId())) {
                issues.add(ValidationIssue.error("SELF_LOOP",
                        "A node cannot connect to itself.", edge.sourceId(), edge.id()));
            }
        }

        // --- Duplicate edges -----------------------------------------------------------
        Set<String> seenPairs = new HashSet<>();
        for (GraphEdge edge : graph.edges()) {
            String pair = edge.sourceId() + "->" + edge.targetId();
            if (!seenPairs.add(pair)) {
                issues.add(ValidationIssue.error("DUPLICATE_EDGE",
                        "Duplicate connection between the same two nodes.", null, edge.id()));
            }
        }

        // --- Node keys ------------------------------------------------------------------
        // A key is how a template addresses a node, so it must be well-formed and unique
        // (case-insensitively, because template lookup is case-insensitive).
        Map<String, GraphNode> byKey = new HashMap<>();
        for (GraphNode node : graph.nodeList()) {
            if (!TemplateVariables.isValidKey(node.key())) {
                issues.add(ValidationIssue.error("INVALID_NODE_KEY",
                        "Node \"" + node.label() + "\" has an invalid key \"" + node.key()
                                + "\". Use 1–64 letters, digits, '_' or '-'.",
                        node.id()));
                continue;
            }
            GraphNode clash = byKey.putIfAbsent(node.key().toLowerCase(), node);
            if (clash != null) {
                issues.add(ValidationIssue.error("DUPLICATE_NODE_KEY",
                        "Nodes \"" + clash.label() + "\" and \"" + node.label()
                                + "\" share the key \"" + node.key() + "\". Keys must be unique.",
                        node.id()));
            }
        }

        // --- Per-node-type arity -------------------------------------------------------
        for (GraphNode node : graph.nodeList()) {
            NodeType type = node.type();
            int in = graph.inDegree(node.id());
            int out = graph.outDegree(node.id());

            if (in < type.minIncoming()) {
                issues.add(ValidationIssue.error("MISSING_INPUT",
                        type.displayName() + " node \"" + node.label()
                                + "\" needs at least " + type.minIncoming() + " incoming connection(s).",
                        node.id()));
            }
            if (type.maxIncoming() != Integer.MAX_VALUE && in > type.maxIncoming()) {
                issues.add(ValidationIssue.error("TOO_MANY_INPUTS",
                        type.displayName() + " node \"" + node.label() + "\" accepts at most "
                                + type.maxIncoming() + " incoming connection(s) but has " + in + ".",
                        node.id()));
            }
            if (out < type.minOutgoing()) {
                issues.add(ValidationIssue.error("MISSING_OUTPUT",
                        type.displayName() + " node \"" + node.label()
                                + "\" needs at least " + type.minOutgoing() + " outgoing connection(s).",
                        node.id()));
            }
            if (type.maxOutgoing() != Integer.MAX_VALUE && out > type.maxOutgoing()) {
                issues.add(ValidationIssue.error("TOO_MANY_OUTPUTS",
                        type.displayName() + " node \"" + node.label() + "\" accepts at most "
                                + type.maxOutgoing() + " outgoing connection(s) but has " + out + ".",
                        node.id()));
            }        }

        // --- Cycles --------------------------------------------------------------------
        CycleResult cycle = detectCycles(graph);
        if (cycle.hasCycle()) {
            List<String> labels = cycle.cycle().stream()
                    .map(id -> graph.node(id))
                    .filter(java.util.Objects::nonNull)
                    .map(GraphNode::label)
                    .toList();
            // The reconstructed cycle already closes on its starting node, so joining the
            // labels already produces "A → B → A". Appending the first label again would
            // duplicate it.
            issues.add(ValidationIssue.error("CYCLE_DETECTED",
                    "The workflow contains a cycle: " + String.join(" → ", labels) + ".",
                    cycle.cycle().isEmpty() ? null : cycle.cycle().get(0)));
        }

        // --- Template references --------------------------------------------------------
        // Checked statically so a typo in {{nodeKey}} is reported before anything runs
        // (and before any paid model call), not as a failure halfway through a run.
        if (!cycle.hasCycle()) {
            for (GraphNode node : graph.nodeList()) {
                if (node.type() != NodeType.PROMPT_TEMPLATE) {
                    continue;
                }
                String template = node.configString("template", "");
                if (template.isBlank()) {
                    issues.add(ValidationIssue.error("EMPTY_TEMPLATE",
                            "Prompt template \"" + node.label() + "\" is empty.", node.id()));
                    continue;
                }
                Set<String> available = new HashSet<>();
                for (UUID ancestor : graph.ancestors(node.id())) {
                    available.add(graph.node(ancestor).key().toLowerCase());
                }
                for (String name : TemplateVariables.referencedIn(template)) {
                    if (!TemplateVariables.isInput(name) && !available.contains(name.toLowerCase())) {
                        issues.add(ValidationIssue.error("UNKNOWN_TEMPLATE_VARIABLE",
                                "Prompt template \"" + node.label() + "\" uses {{" + name
                                        + "}}, but no upstream node has that key.",
                                node.id()));
                    }
                }
            }
        }

        // --- Advisory checks -----------------------------------------------------------
        boolean hasOutput = graph.nodeList().stream().anyMatch(n -> n.type() == NodeType.OUTPUT);
        if (!hasOutput) {
            issues.add(ValidationIssue.warning("NO_OUTPUT_NODE",
                    "Workflow has no Output node, so it will not produce a final result."));
        }
        for (GraphNode node : graph.nodeList()) {
            if (node.type() != NodeType.OUTPUT && graph.outDegree(node.id()) == 0) {
                // Not an error: a half-finished graph is a normal intermediate state while
                // the user is still wiring the canvas. But the result will be discarded.
                issues.add(ValidationIssue.warning("UNCONNECTED_NODE",
                        node.type().displayName() + " \"" + node.label()
                                + "\" is not connected to anything, so its result is discarded.",
                        node.id()));
            }
        }

        issues.sort((a, b) -> {
            // Errors first, and within errors the root cause first: a cycle explains the
            // downstream "too many inputs" complaints, so it must be the message the user
            // sees at the top of the list.
            int bySeverity = Boolean.compare(b.isError(), a.isError());
            if (bySeverity != 0) {
                return bySeverity;
            }
            return Integer.compare(priority(a.code()), priority(b.code()));
        });
        return List.copyOf(issues);
    }

    private static int priority(String code) {
        return switch (code) {
            case "CYCLE_DETECTED" -> 0;
            case "GRAPH_EMPTY" -> 1;
            case "SELF_LOOP" -> 2;
            case "EDGE_SOURCE_MISSING", "EDGE_TARGET_MISSING" -> 3;
            case "MISSING_INPUT" -> 4;
            case "TOO_MANY_INPUTS" -> 5;
            case "TOO_MANY_OUTPUTS" -> 6;
            case "MISSING_OUTPUT" -> 7;
            case "DUPLICATE_EDGE" -> 8;
            case "INVALID_NODE_KEY", "DUPLICATE_NODE_KEY" -> 9;
            case "EMPTY_TEMPLATE", "UNKNOWN_TEMPLATE_VARIABLE" -> 10;
            default -> 50;
        };
    }

    /** Result of a cycle detection pass. */
    public record CycleResult(boolean hasCycle, List<UUID> cycle) {
    }

    /**
     * Computes a deterministic topological order using Kahn's algorithm.
     *
     * <p>Ties are broken by node key so that two runs of the same graph always produce the
     * same ordering — important because the ordering is asserted in tests and shown to users.
     *
     * @throws IllegalStateException when the graph contains a cycle
     */
    public List<GraphNode> topologicalSort(WorkflowGraph graph) {
        Map<UUID, Integer> inDegree = new LinkedHashMap<>();
        for (GraphNode node : graph.nodeList()) {
            inDegree.put(node.id(), graph.inDegree(node.id()));
        }

        // A priority queue keyed on (key, id) keeps the ready set sorted in O(log n) per
        // operation and makes the tie-break impossible to get backwards.
        PriorityQueue<GraphNode> ready = new PriorityQueue<>(BY_KEY);
        for (GraphNode node : graph.nodeList()) {
            if (inDegree.get(node.id()) == 0) {
                ready.add(node);
            }
        }

        List<GraphNode> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            GraphNode current = ready.poll();
            ordered.add(current);
            for (GraphEdge edge : graph.outgoing(current.id())) {
                GraphNode target = graph.node(edge.targetId());
                if (target != null && inDegree.merge(target.id(), -1, Integer::sum) == 0) {
                    ready.add(target);
                }
            }
        }

        if (ordered.size() != graph.nodes().size()) {
            throw new IllegalStateException("Cannot order graph: it contains a cycle.");
        }
        return List.copyOf(ordered);
    }

    private static final Comparator<GraphNode> BY_KEY =
            Comparator.comparing(GraphNode::key).thenComparing(node -> node.id().toString());
}
