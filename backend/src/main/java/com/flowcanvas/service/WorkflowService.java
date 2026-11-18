package com.flowcanvas.service;

import com.flowcanvas.api.dto.WorkflowDtos;
import com.flowcanvas.domain.NodeExecution;
import com.flowcanvas.domain.NodeType;
import com.flowcanvas.domain.Workflow;
import com.flowcanvas.domain.WorkflowEdge;
import com.flowcanvas.domain.WorkflowExecution;
import com.flowcanvas.domain.WorkflowNode;
import com.flowcanvas.domain.WorkflowStatus;
import com.flowcanvas.graph.GraphEdge;
import com.flowcanvas.graph.GraphNode;
import com.flowcanvas.graph.GraphValidator;
import com.flowcanvas.graph.ValidationIssue;
import com.flowcanvas.graph.WorkflowGraph;
import com.flowcanvas.repository.NodeExecutionRepository;
import com.flowcanvas.repository.WorkflowEdgeRepository;
import com.flowcanvas.repository.WorkflowExecutionRepository;
import com.flowcanvas.repository.WorkflowNodeRepository;
import com.flowcanvas.repository.WorkflowRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD and graph persistence for workflows.
 *
 * <p>Save semantics: a workflow is stored as an aggregate. Updating it replaces the whole
 * graph (nodes + edges) in one transaction. That is the honest model for a canvas editor —
 * the client always holds the complete graph — and it avoids the "half-saved graph" class of
 * bugs that incremental patching invites.
 */
@Service
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final NodeExecutionRepository nodeExecutionRepository;
    private final GraphValidator validator;
    private final jakarta.persistence.EntityManager entityManager;

    public WorkflowService(WorkflowRepository workflowRepository,
                           WorkflowNodeRepository nodeRepository,
                           WorkflowEdgeRepository edgeRepository,
                           WorkflowExecutionRepository executionRepository,
                           NodeExecutionRepository nodeExecutionRepository,
                           GraphValidator validator,
                           jakarta.persistence.EntityManager entityManager) {
        this.workflowRepository = workflowRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.executionRepository = executionRepository;
        this.nodeExecutionRepository = nodeExecutionRepository;
        this.validator = validator;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Page<Workflow> list(String search, Pageable pageable) {
        if (search == null || search.isBlank()) {
            return workflowRepository.findAllByOrderByUpdatedAtDesc(pageable);
        }
        return workflowRepository.search(likePattern(search), pageable);
    }

    /** A lower-cased "contains" LIKE pattern with the LIKE metacharacters escaped. */
    static String likePattern(String term) {
        String escaped = term.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Node and edge counts for a set of workflows, in two queries regardless of page size. */
    @Transactional(readOnly = true)
    public Map<UUID, int[]> graphSizes(List<UUID> workflowIds) {
        Map<UUID, int[]> sizes = new java.util.HashMap<>();
        if (workflowIds.isEmpty()) {
            return sizes;
        }
        for (Object[] row : nodeRepository.countByWorkflowIds(workflowIds)) {
            sizes.computeIfAbsent((UUID) row[0], id -> new int[2])[0] = ((Number) row[1]).intValue();
        }
        for (Object[] row : edgeRepository.countByWorkflowIds(workflowIds)) {
            sizes.computeIfAbsent((UUID) row[0], id -> new int[2])[1] = ((Number) row[1]).intValue();
        }
        return sizes;
    }

    /**
     * Creates ({@code id == null}) or updates a workflow together with its graph, atomically.
     *
     * <p>Metadata and graph share one transaction: if the graph cannot be stored, the
     * metadata change rolls back with it, so a failed save never leaves a renamed workflow
     * with its old graph, or a brand-new workflow with no graph at all.
     *
     * <p>Drafts may be <i>invalid</i> — a half-wired canvas or even a cycle is a normal state
     * while editing. What is refused is a graph the database cannot represent faithfully.
     */
    @Transactional
    public Workflow save(UUID id, String name, String description, String status, WorkflowGraph graph) {
        List<ValidationIssue> unstorable = validator.validate(graph).stream()
                .filter(issue -> UNSTORABLE.contains(issue.code()))
                .toList();
        if (!unstorable.isEmpty()) {
            throw new GraphNotStorableException(unstorable);
        }
        Workflow workflow = id == null
                ? create(name, description, status)
                : update(id, name, description, status);
        replaceGraph(workflow.getId(), graph.nodeList(), graph.edges());
        return workflow;
    }

    /** Problems that would violate a database constraint, as opposed to "not runnable yet". */
    private static final Set<String> UNSTORABLE = Set.of(
            "INVALID_NODE_KEY", "DUPLICATE_NODE_KEY", "DUPLICATE_EDGE", "SELF_LOOP");

    @Transactional(readOnly = true)
    public Workflow getEntity(UUID id) {
        return workflowRepository.findById(id)
                .orElseThrow(() -> new WorkflowNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<WorkflowNode> findNodes(UUID workflowId) {
        return nodeRepository.findByWorkflowId(workflowId);
    }

    @Transactional(readOnly = true)
    public List<WorkflowEdge> findEdges(UUID workflowId) {
        return edgeRepository.findByWorkflowId(workflowId);
    }

    @Transactional(readOnly = true)
    public WorkflowGraph loadGraph(UUID workflowId) {
        return new WorkflowGraph(
                findNodes(workflowId).stream()
                        .map(n -> new GraphNode(n.getId(), n.getNodeKey(), n.nodeType(), n.getLabel(),
                                n.getPositionX(), n.getPositionY(), n.getConfig()))
                        .toList(),
                findEdges(workflowId).stream()
                        .map(e -> new GraphEdge(e.getId(), e.getSourceId(), e.getTargetId(),
                                e.getSourceHandle(), e.getTargetHandle()))
                        .toList());
    }

    @Transactional
    public Workflow create(String name, String description, String status) {
        Workflow workflow = new Workflow(UUID.randomUUID(), name, description);
        if (status != null && !status.isBlank()) {
            workflow.setStatus(normaliseStatus(status));
        }
        return workflowRepository.save(workflow);
    }

    @Transactional
    public Workflow update(UUID id, String name, String description, String status) {
        Workflow workflow = getEntity(id);
        if (name != null && !name.isBlank()) {
            workflow.setName(name.trim());
        }
        if (description != null) {
            workflow.setDescription(description.isBlank() ? null : description.trim());
        }
        if (status != null && !status.isBlank()) {
            workflow.setStatus(normaliseStatus(status));
        }
        workflow.touch();
        return workflowRepository.save(workflow);
    }

    /**
     * Replaces the entire graph of a workflow.
     *
     * <p>Nodes and edges are deleted and re-inserted rather than diffed. For graphs of this
     * size (tens of nodes) the write cost is irrelevant, and the correctness win — no stale
     * rows, no orphaned edges, no partial state — is total.
     */
    @Transactional
    public void replaceGraph(UUID workflowId, List<GraphNode> nodes, List<GraphEdge> edges) {
        Workflow workflow = getEntity(workflowId);

        edgeRepository.deleteByWorkflowId(workflowId);
        nodeRepository.deleteByWorkflowId(workflowId);

        // A bulk JPQL delete bypasses the persistence context, so the previously loaded
        // entities are still managed and would collide with the rows we are about to
        // insert (same primary keys). Clearing the context forces a clean slate.
        entityManager.flush();
        entityManager.clear();

        Set<UUID> nodeIds = new HashSet<>();
        for (GraphNode node : nodes) {
            nodeIds.add(node.id());
            nodeRepository.save(new WorkflowNode(
                    node.id(), workflowId, node.key(), node.type(), node.label(),
                    node.positionX(), node.positionY(), node.mutableConfig()));
        }

        for (GraphEdge edge : edges) {
            // Defensive: never persist an edge pointing at a node that is not in the graph.
            if (!nodeIds.contains(edge.sourceId()) || !nodeIds.contains(edge.targetId())) {
                continue;
            }
            edgeRepository.save(new WorkflowEdge(
                    edge.id(), workflowId, edge.sourceId(), edge.targetId(),
                    edge.sourceHandle(), edge.targetHandle()));
        }

        workflow.touch();
        workflowRepository.save(workflow);
    }

    @Transactional
    public void delete(UUID id) {
        Workflow workflow = getEntity(id);
        workflowRepository.delete(workflow);
        // Flush explicitly: the node/edge rows are removed by the database's ON DELETE
        // CASCADE, which only happens once the workflow row's DELETE is actually sent.
        // Without this, a caller querying immediately afterwards would still see the
        // orphaned rows.
        entityManager.flush();
    }

    @Transactional(readOnly = true)
    public List<WorkflowDtos.ExecutionOrderEntry> executionOrder(WorkflowGraph graph) {
        try {
            List<WorkflowDtos.ExecutionOrderEntry> entries = new ArrayList<>();
            List<GraphNode> ordered = validator.topologicalSort(graph);
            for (int i = 0; i < ordered.size(); i++) {
                GraphNode node = ordered.get(i);
                entries.add(new WorkflowDtos.ExecutionOrderEntry(
                        i + 1, node.id(), node.key(), node.label(), node.type().name()));
            }
            return entries;
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }

    @Transactional(readOnly = true)
    public List<ValidationIssue> validateGraph(WorkflowGraph graph) {
        return validator.validate(graph);
    }

    @Transactional
    public WorkflowExecution saveExecution(WorkflowExecution execution) {
        return executionRepository.save(execution);
    }

    @Transactional(readOnly = true)
    public WorkflowExecution findExecution(UUID id) {
        return executionRepository.findById(id)
                .orElseThrow(() -> new ExecutionNotFoundException(id));
    }

    @Transactional
    public void saveNodeExecutions(List<NodeExecution> executions) {
        nodeExecutionRepository.saveAll(executions);
    }

    @Transactional
    public NodeExecution saveNodeExecution(NodeExecution execution) {
        return nodeExecutionRepository.save(execution);
    }

    /**
     * Closes every run that was still in flight when the server last stopped.
     *
     * <p>The worker pool is in-memory, so a restart orphans any running execution. Without
     * this, such a run would report {@code RUNNING} forever and the UI would poll it forever.
     */
    @Transactional
    public int failInterruptedRuns() {
        String reason = "Interrupted: the server restarted before the run finished.";
        Instant now = Instant.now();
        nodeExecutionRepository.skipUnfinished(reason, now);
        return executionRepository.failUnfinished(reason, now);
    }

    @Transactional(readOnly = true)
    public List<NodeExecution> findNodeExecutions(UUID executionId) {
        return nodeExecutionRepository.findByExecutionIdOrderBySequenceAsc(executionId);
    }

    @Transactional(readOnly = true)
    public List<WorkflowExecution> findExecutions(UUID workflowId) {
        return executionRepository.findTop20ByWorkflowIdOrderByStartedAtDesc(workflowId);
    }

    private String normaliseStatus(String status) {
        try {
            return WorkflowStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown workflow status: " + status);
        }
    }

    /** Raised when a workflow id does not exist. */
    public static class WorkflowNotFoundException extends RuntimeException {
        public WorkflowNotFoundException(UUID id) {
            super("Workflow not found: " + id);
        }
    }

    /** Raised when a graph cannot be persisted because it would break a schema constraint. */
    public static class GraphNotStorableException extends RuntimeException {
        private final List<ValidationIssue> issues;

        public GraphNotStorableException(List<ValidationIssue> issues) {
            super(issues.get(0).message());
            this.issues = List.copyOf(issues);
        }

        public List<ValidationIssue> issues() {
            return issues;
        }
    }

    /** Raised when an execution id does not exist. */
    public static class ExecutionNotFoundException extends RuntimeException {
        public ExecutionNotFoundException(UUID id) {
            super("Execution not found: " + id);
        }
    }
}
