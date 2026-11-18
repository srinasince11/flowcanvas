package com.flowcanvas.service;

import com.flowcanvas.domain.ExecutionStatus;
import com.flowcanvas.domain.NodeExecution;
import com.flowcanvas.domain.NodeExecutionStatus;
import com.flowcanvas.domain.Workflow;
import com.flowcanvas.domain.WorkflowExecution;
import com.flowcanvas.engine.ExecutionResult;
import com.flowcanvas.engine.WorkflowEngine;
import com.flowcanvas.graph.WorkflowGraph;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs workflows and records their history.
 *
 * <p>Execution is <b>asynchronous</b>: the HTTP request returns {@code 202 Accepted} with an
 * execution id immediately, and the client polls {@code GET /api/v1/executions/{id}}. This
 * keeps a slow AI call from holding an HTTP thread open and gives the UI a real run to
 * animate instead of a spinner over a frozen request.
 *
 * <p>Each node's result is written to {@code node_executions} as it completes, so a partially
 * finished run is still fully inspectable.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final WorkflowEngine engine;
    private final WorkflowService workflowService;
    private final ExecutionRunner executionRunner;
    private final Executor executor;

    public ExecutionService(WorkflowEngine engine, WorkflowService workflowService,
                            ExecutionRunner executionRunner, Executor workflowExecutor) {
        this.engine = engine;
        this.workflowService = workflowService;
        this.executionRunner = executionRunner;
        this.executor = workflowExecutor;
    }

    /**
     * Starts a run and returns immediately.
     *
     * <p>The workflow graph is snapshotted synchronously, before the async hand-off, so a
     * concurrent edit of the workflow cannot change what is being executed.
     *
     * <p>The transaction structure here is the subtle part:
     * <ul>
     *   <li>This method is deliberately <b>not</b> {@code @Transactional}. The execution row
     *       is written through {@code workflowService.saveExecution}, whose transaction
     *       commits the moment that call returns.</li>
     *   <li>The async worker is dispatched only <b>after</b> that commit. Otherwise the
     *       worker could insert {@code node_executions} rows referencing an execution row
     *       that is not visible to it yet, and the foreign key would fail.</li>
     * </ul>
     */
    public WorkflowExecution startAsync(UUID workflowId) {
        Workflow workflow = workflowService.getEntity(workflowId);
        WorkflowGraph graph = workflowService.loadGraph(workflowId);

        WorkflowExecution execution = new WorkflowExecution(UUID.randomUUID(), workflowId);
        execution.setStatus(ExecutionStatus.RUNNING.name());
        execution.setStartedAt(Instant.now());
        // Committed by this call — see the method javadoc.
        WorkflowExecution saved = workflowService.saveExecution(execution);

        CompletableFuture.runAsync(() -> executionRunner.run(saved.getId(), graph), executor);
        return saved;
    }

    /**
     * Runs a workflow synchronously without recording it — used by tests.
     *
     * <p>Not {@code @Transactional}: the graph is loaded in its own short read transaction,
     * and no connection should be held while model calls are in flight.
     */
    public ExecutionResult runSync(UUID workflowId) {
        WorkflowGraph graph = workflowService.loadGraph(workflowId);
        return engine.execute(graph);
    }

    /** Re-reads an execution and its node results. */
    @Transactional(readOnly = true)
    public ExecutionSnapshot snapshot(UUID executionId) {
        WorkflowExecution execution = workflowService.findExecution(executionId);
        List<NodeExecution> nodeExecutions = workflowService.findNodeExecutions(executionId);
        return new ExecutionSnapshot(execution, nodeExecutions);
    }

    /** A run plus its per-node records. */
    public record ExecutionSnapshot(WorkflowExecution execution, List<NodeExecution> nodeExecutions) {
        public ExecutionSnapshot {
            nodeExecutions = nodeExecutions == null ? List.of() : nodeExecutions;
        }
    }

    /** Maps a node record to its status enum, defaulting to PENDING for unknown values. */
    public static NodeExecutionStatus statusOf(NodeExecution record) {
        try {
            return NodeExecutionStatus.valueOf(record.getStatus());
        } catch (IllegalArgumentException exception) {
            return NodeExecutionStatus.PENDING;
        }
    }
}
