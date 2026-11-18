package com.flowcanvas.api;

import com.flowcanvas.api.dto.ExecutionDtos;
import com.flowcanvas.api.dto.WorkflowDtos;
import com.flowcanvas.domain.NodeExecution;
import com.flowcanvas.domain.Workflow;
import com.flowcanvas.domain.WorkflowExecution;
import com.flowcanvas.engine.ExecutionResult;
import com.flowcanvas.engine.NodeExecutionResult;
import com.flowcanvas.graph.GraphEdge;
import com.flowcanvas.graph.GraphNode;
import com.flowcanvas.graph.ValidationIssue;
import com.flowcanvas.graph.WorkflowGraph;
import com.flowcanvas.service.ExecutionService;
import com.flowcanvas.service.ExampleWorkflowService;
import com.flowcanvas.service.WorkflowService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The workflow resource.
 *
 * <p>Route summary:
 * <pre>
 * GET    /api/v1/workflows            list (paged, searchable)
 * POST   /api/v1/workflows            create
 * GET    /api/v1/workflows/{id}       read with the full graph
 * PUT    /api/v1/workflows/{id}       update metadata and/or the graph
 * DELETE /api/v1/workflows/{id}       delete
 * POST   /api/v1/workflows/validate   validate a graph without saving it
 * POST   /api/v1/workflows/{id}/execute  start a run (202 Accepted)
 * GET    /api/v1/workflows/{id}/executions  run history
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final ExecutionService executionService;
    private final ExampleWorkflowService exampleService;
    private final GraphMapper mapper;

    public WorkflowController(WorkflowService workflowService,
                              ExecutionService executionService,
                              ExampleWorkflowService exampleService,
                              GraphMapper mapper) {
        this.workflowService = workflowService;
        this.executionService = executionService;
        this.exampleService = exampleService;
        this.mapper = mapper;
    }

    @GetMapping
    public WorkflowDtos.PageResponse<WorkflowDtos.WorkflowSummary> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100));
        var result = workflowService.list(search, pageable);
        var sizes = workflowService.graphSizes(
                result.getContent().stream().map(Workflow::getId).toList());
        List<WorkflowDtos.WorkflowSummary> summaries = result.getContent().stream()
                .map(workflow -> toSummary(workflow, sizes.getOrDefault(workflow.getId(), new int[2])))
                .toList();
        return new WorkflowDtos.PageResponse<>(summaries, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @PostMapping
    public ResponseEntity<WorkflowDtos.WorkflowResponse> create(
            @Valid @RequestBody WorkflowDtos.WorkflowRequest request) {
        WorkflowGraph graph = mapper.toGraph(request.nodes(), request.edges());
        Workflow workflow = workflowService.save(
                null, request.name(), request.description(), request.status(), graph);
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}").buildAndExpand(workflow.getId()).toUri())
                .body(toResponse(workflow));
    }

    @GetMapping("/{id}")
    public WorkflowDtos.WorkflowResponse get(@PathVariable UUID id) {
        Workflow workflow = workflowService.getEntity(id);
        return toResponse(workflow);
    }

    @PutMapping("/{id}")
    public WorkflowDtos.WorkflowResponse update(@PathVariable UUID id,
                                                @Valid @RequestBody WorkflowDtos.WorkflowRequest request) {
        WorkflowGraph graph = mapper.toGraph(request.nodes(), request.edges());
        Workflow workflow = workflowService.save(
                id, request.name(), request.description(), request.status(), graph);
        return toResponse(workflow);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        workflowService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Validates a graph without persisting it. The frontend calls this before every run so
     * the user sees problems (cycles, illegal connections) before anything executes.
     */
    @PostMapping("/validate")
    public WorkflowDtos.ValidationResponse validate(
            @Valid @RequestBody WorkflowDtos.ValidateRequest request) {
        WorkflowGraph graph = mapper.toGraph(request.nodes(), request.edges());
        List<ValidationIssue> issues = workflowService.validateGraph(graph);
        boolean valid = !ValidationIssue.hasErrors(issues);
        return new WorkflowDtos.ValidationResponse(
                valid,
                issues.stream().map(mapper::toPayload).toList(),
                valid ? workflowService.executionOrder(graph) : List.of());
    }

    /** Starts a run. Returns 202 with the execution id; poll {@code /api/v1/executions/{id}}. */
    @PostMapping("/{id}/execute")
    public ResponseEntity<ExecutionDtos.ExecutionAccepted> execute(@PathVariable UUID id) {
        WorkflowExecution execution = executionService.startAsync(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ExecutionDtos.ExecutionAccepted(
                        execution.getId(), execution.getWorkflowId(), execution.getStatus()));
    }

    /** Run history for a workflow, newest first. */
    @GetMapping("/{id}/executions")
    public List<ExecutionDtos.ExecutionSummary> executions(@PathVariable UUID id) {
        return workflowService.findExecutions(id).stream()
                .map(e -> new ExecutionDtos.ExecutionSummary(
                        e.getId(), e.getStatus(), e.getStartedAt(), e.getFinishedAt(),
                        e.getDurationMs(), e.getError()))
                .toList();
    }

    /** The built-in example catalogue, returned as graphs the client can load directly. */
    @GetMapping("/examples")
    public List<ExampleResponse> examples() {
        List<ExampleResponse> responses = new ArrayList<>();
        for (ExampleWorkflowService.Example example : exampleService.examples()) {
            responses.add(new ExampleResponse(
                    example.name(),
                    example.description(),
                    example.nodes().stream().map(mapper::toPayload).toList(),
                    example.edges().stream().map(mapper::toPayload).toList()));
        }
        return responses;
    }

    /** A ready-made workflow graph. */
    public record ExampleResponse(
            String name,
            String description,
            List<WorkflowDtos.NodePayload> nodes,
            List<WorkflowDtos.EdgePayload> edges
    ) {
    }

    private WorkflowDtos.WorkflowSummary toSummary(Workflow workflow, int[] size) {
        return new WorkflowDtos.WorkflowSummary(
                workflow.getId(), workflow.getName(), workflow.getDescription(), workflow.getStatus(),
                size[0], size[1], workflow.getCreatedAt(), workflow.getUpdatedAt());
    }

    private WorkflowDtos.WorkflowResponse toResponse(Workflow workflow) {
        List<WorkflowDtos.NodePayload> nodes = workflowService.findNodes(workflow.getId()).stream()
                .map(n -> mapper.toPayload(new GraphNode(
                        n.getId(), n.getNodeKey(), n.nodeType(), n.getLabel(),
                        n.getPositionX(), n.getPositionY(), n.getConfig())))
                .toList();
        List<WorkflowDtos.EdgePayload> edges = workflowService.findEdges(workflow.getId()).stream()
                .map(e -> mapper.toPayload(new GraphEdge(
                        e.getId(), e.getSourceId(), e.getTargetId(),
                        e.getSourceHandle(), e.getTargetHandle())))
                .toList();
        return new WorkflowDtos.WorkflowResponse(
                workflow.getId(), workflow.getName(), workflow.getDescription(), workflow.getStatus(),
                nodes, edges, workflow.getCreatedAt(), workflow.getUpdatedAt());
    }
}
