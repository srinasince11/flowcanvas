package com.flowcanvas.api;

import com.flowcanvas.api.dto.ExecutionDtos;
import com.flowcanvas.domain.NodeExecution;
import com.flowcanvas.domain.Workflow;
import com.flowcanvas.domain.WorkflowExecution;
import com.flowcanvas.service.ExecutionService;
import com.flowcanvas.service.WorkflowService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The execution resource. Separate from the workflow resource because runs are first-class
 * records with their own lifecycle, not a side effect of a workflow.
 */
@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionController {

    private final ExecutionService executionService;
    private final WorkflowService workflowService;

    public ExecutionController(ExecutionService executionService, WorkflowService workflowService) {
        this.executionService = executionService;
        this.workflowService = workflowService;
    }

    /** Poll this endpoint while a run is in flight. */
    @GetMapping("/{id}")
    public ExecutionDtos.ExecutionResponse get(@PathVariable UUID id) {
        ExecutionService.ExecutionSnapshot snapshot = executionService.snapshot(id);
        WorkflowExecution execution = snapshot.execution();

        String workflowName = null;
        try {
            Workflow workflow = workflowService.getEntity(execution.getWorkflowId());
            workflowName = workflow.getName();
        } catch (RuntimeException ignored) {
            // The workflow was deleted after the run started; the run is still reportable.
        }

        return new ExecutionDtos.ExecutionResponse(
                execution.getId(),
                execution.getWorkflowId(),
                workflowName,
                execution.getStatus(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.getDurationMs(),
                execution.getError(),
                snapshot.nodeExecutions().stream().map(this::toResponse).toList());
    }

    private ExecutionDtos.NodeExecutionResponse toResponse(NodeExecution record) {
        return new ExecutionDtos.NodeExecutionResponse(
                record.getNodeId(),
                record.getNodeKey(),
                record.getLabel(),
                record.getNodeType(),
                record.getSequence(),
                record.getStatus(),
                record.getOutput(),
                record.getError(),
                record.getDurationMs(),
                record.getStartedAt(),
                record.getFinishedAt());
    }
}
