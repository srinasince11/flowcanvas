package com.flowcanvas.service;

import com.flowcanvas.domain.ExecutionStatus;
import com.flowcanvas.domain.NodeExecution;
import com.flowcanvas.domain.NodeExecutionStatus;
import com.flowcanvas.domain.WorkflowExecution;
import com.flowcanvas.engine.ExecutionListener;
import com.flowcanvas.engine.ExecutionResult;
import com.flowcanvas.engine.NodeExecutionResult;
import com.flowcanvas.engine.WorkflowEngine;
import com.flowcanvas.graph.GraphNode;
import com.flowcanvas.graph.WorkflowGraph;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs a workflow on a worker thread and records its progress as it happens.
 *
 * <p>Three properties matter here, and each one was a bug in an earlier design:
 *
 * <ol>
 *   <li><b>Progress is visible while the run is in flight.</b> The engine reports every
 *       lifecycle event; each one is committed immediately. The plan is written up front as
 *       {@code PENDING} rows, then each node flips to {@code RUNNING} and to its terminal
 *       state, so a client polling {@code GET /executions/{id}} watches the run advance.</li>
 *   <li><b>No database connection is held across a model call.</b> This method is
 *       deliberately <i>not</i> {@code @Transactional}: every write goes through
 *       {@link WorkflowService}, whose methods are short transactions of their own. A
 *       transaction wrapped around the whole run would pin a pooled connection for as long
 *       as the slowest model takes to answer, and a handful of slow runs would exhaust the
 *       pool for the entire API.</li>
 *   <li><b>A run always terminates.</b> Any unexpected failure — including a write failing
 *       because the workflow was deleted mid-run — marks the execution {@code FAILED}
 *       rather than leaving it {@code RUNNING} forever.</li>
 * </ol>
 *
 * <p>It lives in its own bean, separate from {@link ExecutionService}, so that calls into
 * {@link WorkflowService} go through Spring's transactional proxy.
 */
@Service
public class ExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRunner.class);

    private final WorkflowEngine engine;
    private final WorkflowService workflowService;

    public ExecutionRunner(WorkflowEngine engine, WorkflowService workflowService) {
        this.engine = engine;
        this.workflowService = workflowService;
    }

    /** Executes the graph and records the run. Never throws. */
    public void run(UUID executionId, WorkflowGraph graph) {
        try {
            ExecutionResult result = engine.execute(graph, new PersistingListener(executionId));
            finish(executionId, result.status(), result.durationMs(), result.error());
            log.info("Execution {} finished with status {} in {} ms",
                    executionId, result.status(), result.durationMs());
        } catch (RuntimeException exception) {
            log.error("Execution {} aborted unexpectedly", executionId, exception);
            try {
                finish(executionId, ExecutionStatus.FAILED, null,
                        "The run stopped unexpectedly: " + exception.getMessage());
            } catch (RuntimeException secondary) {
                // Most likely the workflow (and with it this execution) was deleted mid-run.
                log.warn("Could not record the failure of execution {}: {}",
                        executionId, secondary.getMessage());
            }
        }
    }

    private void finish(UUID executionId, ExecutionStatus status, Long durationMs, String error) {
        WorkflowExecution execution = workflowService.findExecution(executionId);
        execution.setStatus(status.name());
        execution.setFinishedAt(Instant.now());
        if (durationMs != null) {
            execution.setDurationMs(durationMs);
        } else if (execution.getStartedAt() != null) {
            execution.setDurationMs(Instant.now().toEpochMilli() - execution.getStartedAt().toEpochMilli());
        }
        execution.setError(error);
        workflowService.saveExecution(execution);
    }

    /** Mirrors engine events into {@code node_executions}, one short transaction per event. */
    private final class PersistingListener implements ExecutionListener {

        private final UUID executionId;
        private final Map<UUID, NodeExecution> records = new HashMap<>();

        private PersistingListener(UUID executionId) {
            this.executionId = executionId;
        }

        @Override
        public void onPlanned(List<GraphNode> ordered) {
            for (int index = 0; index < ordered.size(); index++) {
                GraphNode node = ordered.get(index);
                records.put(node.id(), new NodeExecution(UUID.randomUUID(), executionId, node.id(),
                        node.key(), node.type(), node.label(), index + 1));
            }
            workflowService.saveNodeExecutions(List.copyOf(records.values()));
        }

        @Override
        public void onNodeStarted(GraphNode node, int sequence, Instant startedAt) {
            NodeExecution record = records.get(node.id());
            record.setStatus(NodeExecutionStatus.RUNNING.name());
            record.setStartedAt(startedAt);
            workflowService.saveNodeExecution(record);
        }

        @Override
        public void onNodeFinished(NodeExecutionResult result) {
            NodeExecution record = records.get(result.nodeId());
            record.setStatus(result.status().name());
            record.setOutput(result.output());
            record.setError(result.error());
            record.setDurationMs(result.durationMs());
            record.setStartedAt(result.startedAt());
            record.setFinishedAt(result.finishedAt());
            workflowService.saveNodeExecution(record);
        }
    }
}
