package com.flowcanvas.engine;

import com.flowcanvas.domain.ExecutionStatus;
import com.flowcanvas.domain.NodeExecutionStatus;
import com.flowcanvas.domain.NodeType;
import com.flowcanvas.graph.GraphEdge;
import com.flowcanvas.graph.GraphNode;
import com.flowcanvas.graph.GraphValidator;
import com.flowcanvas.graph.ValidationIssue;
import com.flowcanvas.graph.WorkflowGraph;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes a validated workflow graph in dependency order.
 *
 * <ul>
 *   <li><b>Topological order, not insertion order.</b> Nodes run only after every
 *       predecessor has produced a value, so a node always sees complete inputs.</li>
 *   <li><b>Failure isolation.</b> When a node fails, its descendants are marked
 *       {@code SKIPPED} rather than being attempted with missing inputs, and the remaining
 *       independent branches still run. One bad AI call does not destroy the whole run.</li>
 *   <li><b>Lexical scoping for templates.</b> A node sees the outputs of its own ancestors
 *       — and only those. A node on an unrelated branch is invisible even if it happens to
 *       run earlier, so a template's meaning never depends on tie-breaking.</li>
 *   <li><b>Observable.</b> Every lifecycle step is reported to an {@link ExecutionListener},
 *       which is how a run's progress reaches the database while it is still running.</li>
 * </ul>
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final GraphValidator validator;
    private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);

    public WorkflowEngine(GraphValidator validator, List<NodeExecutor> executorList) {
        this.validator = validator;
        for (NodeExecutor executor : executorList) {
            this.executors.put(executor.type(), executor);
        }
    }

    /** Executes a graph without observing it. */
    public ExecutionResult execute(WorkflowGraph graph) {
        return execute(graph, ExecutionListener.NONE);
    }

    /** Executes a graph, validating first. Never throws for graph-level problems. */
    public ExecutionResult execute(WorkflowGraph graph, ExecutionListener listener) {
        long runStart = System.nanoTime();

        List<ValidationIssue> issues = validator.validate(graph);
        if (ValidationIssue.hasErrors(issues)) {
            long errors = issues.stream().filter(ValidationIssue::isError).count();
            String first = issues.stream().filter(ValidationIssue::isError)
                    .findFirst().map(ValidationIssue::message).orElse("");
            return new ExecutionResult(ExecutionStatus.FAILED, List.of(), issues,
                    "Workflow validation failed with " + errors + " error(s). " + first, 0L);
        }

        List<GraphNode> ordered = validator.topologicalSort(graph);
        listener.onPlanned(ordered);

        Map<UUID, String> outputs = new HashMap<>();
        Set<UUID> blocked = new HashSet<>();
        List<NodeExecutionResult> results = new ArrayList<>();

        for (int index = 0; index < ordered.size(); index++) {
            GraphNode node = ordered.get(index);
            int sequence = index + 1;
            Instant startedAt = Instant.now();

            // Predecessors always run first, so "blocked" is complete for them by now: a node
            // is skipped when any direct producer failed or was itself skipped.
            boolean upstreamFailed = graph.incoming(node.id()).stream()
                    .anyMatch(edge -> blocked.contains(edge.sourceId()));
            if (upstreamFailed) {
                blocked.add(node.id());
                NodeExecutionResult skipped = new NodeExecutionResult(node.id(), node.key(), node.label(),
                        node.type(), sequence, NodeExecutionStatus.SKIPPED, null,
                        "Skipped because an upstream node failed.", 0L, startedAt, startedAt);
                results.add(skipped);
                listener.onNodeFinished(skipped);
                continue;
            }

            listener.onNodeStarted(node, sequence, startedAt);
            NodeInput nodeInput = new NodeInput(node, directInputs(graph, node, outputs),
                    ancestorOutputs(graph, node, outputs));

            long nodeStart = System.nanoTime();
            NodeExecutionResult result;
            try {
                NodeExecutor executor = executors.get(node.type());
                if (executor == null) {
                    throw new NodeExecutionException("No executor registered for node type " + node.type());
                }
                String output = executor.execute(nodeInput);
                outputs.put(node.id(), output);
                result = new NodeExecutionResult(node.id(), node.key(), node.label(), node.type(),
                        sequence, NodeExecutionStatus.SUCCESS, output, null,
                        elapsedMs(nodeStart), startedAt, Instant.now());
            } catch (RuntimeException exception) {
                blocked.add(node.id());
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                log.warn("Node {} ({}) failed: {}", node.key(), node.type(), message);
                result = new NodeExecutionResult(node.id(), node.key(), node.label(), node.type(),
                        sequence, NodeExecutionStatus.FAILED, null, message,
                        elapsedMs(nodeStart), startedAt, Instant.now());
            }
            results.add(result);
            listener.onNodeFinished(result);
        }

        boolean anyFailed = results.stream().anyMatch(r -> r.status() == NodeExecutionStatus.FAILED);
        return new ExecutionResult(
                anyFailed ? ExecutionStatus.FAILED : ExecutionStatus.SUCCESS,
                results,
                issues,
                anyFailed ? "One or more nodes failed." : null,
                elapsedMs(runStart));
    }

    /**
     * The outputs of the node's direct producers, keyed by node key and ordered by key so a
     * multi-input node sees its inputs in the same order on every run.
     */
    private static Map<String, String> directInputs(WorkflowGraph graph, GraphNode node,
                                                    Map<UUID, String> outputs) {
        Map<String, String> inputs = new LinkedHashMap<>();
        graph.incoming(node.id()).stream()
                .map(GraphEdge::sourceId)
                .map(graph::node)
                .filter(source -> source != null && outputs.containsKey(source.id()))
                .sorted(Comparator.comparing(GraphNode::key))
                .forEach(source -> inputs.put(source.key(), outputs.get(source.id())));
        return inputs;
    }

    /** The outputs of every ancestor of the node: the variables a template may reference. */
    private static Map<String, String> ancestorOutputs(WorkflowGraph graph, GraphNode node,
                                                      Map<UUID, String> outputs) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (UUID ancestor : graph.ancestors(node.id())) {
            String value = outputs.get(ancestor);
            if (value != null) {
                variables.put(graph.node(ancestor).key(), value);
            }
        }
        return Map.copyOf(variables);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
