package com.flowcanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcanvas.domain.ExecutionStatus;
import com.flowcanvas.domain.NodeExecutionStatus;
import com.flowcanvas.domain.WorkflowExecution;
import com.flowcanvas.service.ExecutionService;
import com.flowcanvas.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Execution integration tests: a workflow is persisted, executed through the real engine
 * against the mock AI provider, and the recorded run is inspected.
 */
class ExecutionApiTest extends AbstractWebTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    private UUID persistChain(String text) {
        var input = new com.flowcanvas.graph.GraphNode(
                UUID.randomUUID(), "input", com.flowcanvas.domain.NodeType.TEXT_INPUT, "Input", 0, 0,
                Map.of("text", text));
        var template = new com.flowcanvas.graph.GraphNode(
                UUID.randomUUID(), "template", com.flowcanvas.domain.NodeType.PROMPT_TEMPLATE, "Template", 200, 0,
                Map.of("template", "Question: {{input}}"));
        var model = new com.flowcanvas.graph.GraphNode(
                UUID.randomUUID(), "model", com.flowcanvas.domain.NodeType.AI_MODEL, "Model", 400, 0,
                Map.of("model", "test-model", "systemPrompt", "Be helpful", "temperature", 0.5));
        var output = new com.flowcanvas.graph.GraphNode(
                UUID.randomUUID(), "output", com.flowcanvas.domain.NodeType.OUTPUT, "Output", 600, 0,
                Map.of("format", "text"));

        var workflow = workflowService.create("Exec " + UUID.randomUUID(), null, "DRAFT");
        workflowService.replaceGraph(workflow.getId(), List.of(input, template, model, output), List.of(
                new com.flowcanvas.graph.GraphEdge(UUID.randomUUID(), input.id(), template.id(), "out", "in"),
                new com.flowcanvas.graph.GraphEdge(UUID.randomUUID(), template.id(), model.id(), "out", "in"),
                new com.flowcanvas.graph.GraphEdge(UUID.randomUUID(), model.id(), output.id(), "out", "in")));
        return workflow.getId();
    }

    @Test
    @DisplayName("exposes progress while the run is still in flight")
    void exposesLiveProgress() throws Exception {
        // [[slow]] makes the mock model take ~750 ms: long enough to observe the run mid-way.
        UUID workflowId = persistChain("[[slow]] live progress");
        UUID executionId = executionService.startAsync(workflowId).getId();

        ExecutionService.ExecutionSnapshot midRun = null;
        for (int i = 0; i < 200; i++) {
            var snapshot = executionService.snapshot(executionId);
            boolean modelRunning = snapshot.nodeExecutions().stream()
                    .anyMatch(n -> n.getNodeKey().equals("model") && n.getStatus().equals("RUNNING"));
            if (modelRunning) {
                midRun = snapshot;
                break;
            }
            Thread.sleep(10);
        }

        assertThat(midRun).as("a snapshot taken while the model node was running").isNotNull();
        assertThat(midRun.execution().getStatus()).isEqualTo("RUNNING");
        var statuses = midRun.nodeExecutions().stream()
                .collect(java.util.stream.Collectors.toMap(n -> n.getNodeKey(), n -> n.getStatus()));
        // The whole plan is visible up front, and the run is caught exactly where it is.
        assertThat(statuses).containsEntry("input", "SUCCESS")
                .containsEntry("template", "SUCCESS")
                .containsEntry("model", "RUNNING")
                .containsEntry("output", "PENDING");

        var finished = awaitCompletion(executionId);
        assertThat(finished.execution().getStatus()).isEqualTo("SUCCESS");
        assertThat(finished.nodeExecutions()).allSatisfy(n -> {
            assertThat(n.getFinishedAt()).isNotNull();
            assertThat(n.getStartedAt()).isBeforeOrEqualTo(n.getFinishedAt());
        });
    }

    @Test
    @DisplayName("fails runs left in flight by a previous server process")
    void failsInterruptedRuns() {
        UUID workflowId = persistChain("orphan");
        WorkflowExecution orphan = new WorkflowExecution(UUID.randomUUID(), workflowId);
        orphan.setStatus("RUNNING");
        orphan.setStartedAt(java.time.Instant.now());
        workflowService.saveExecution(orphan);

        assertThat(workflowService.failInterruptedRuns()).isGreaterThanOrEqualTo(1);

        WorkflowExecution closed = workflowService.findExecution(orphan.getId());
        assertThat(closed.getStatus()).isEqualTo("FAILED");
        assertThat(closed.getError()).contains("Interrupted");
        assertThat(closed.getFinishedAt()).isNotNull();
    }

    private ExecutionService.ExecutionSnapshot awaitCompletion(UUID executionId) throws InterruptedException {
        ExecutionService.ExecutionSnapshot snapshot = executionService.snapshot(executionId);
        for (int i = 0; i < 200 && "RUNNING".equals(snapshot.execution().getStatus()); i++) {
            Thread.sleep(25);
            snapshot = executionService.snapshot(executionId);
        }
        return snapshot;
    }

    @Test
    @DisplayName("executes a workflow and records every node in dependency order")
    void executesAndRecordsNodes() throws Exception {
        UUID workflowId = persistChain("What is FlowCanvas?");

        WorkflowExecution execution = executionService.startAsync(workflowId);
        UUID executionId = execution.getId();

        // Poll until the async run finishes.
        ExecutionService.ExecutionSnapshot snapshot = null;
        for (int i = 0; i < 100; i++) {
            snapshot = executionService.snapshot(executionId);
            if (!"RUNNING".equals(snapshot.execution().getStatus())
                    && !"PENDING".equals(snapshot.execution().getStatus())) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.execution().getStatus()).isEqualTo(ExecutionStatus.SUCCESS.name());
        assertThat(snapshot.execution().getDurationMs()).isNotNull();
        assertThat(snapshot.nodeExecutions()).hasSize(4);

        var byKey = snapshot.nodeExecutions().stream()
                .collect(java.util.stream.Collectors.toMap(n -> n.getNodeKey(), n -> n));
        assertThat(byKey.get("input").getSequence()).isEqualTo(1);
        assertThat(byKey.get("template").getSequence()).isEqualTo(2);
        assertThat(byKey.get("model").getSequence()).isEqualTo(3);
        assertThat(byKey.get("output").getSequence()).isEqualTo(4);
        assertThat(byKey.get("template").getOutput()).isEqualTo("Question: What is FlowCanvas?");
        assertThat(byKey.get("output").getStatus()).isEqualTo(NodeExecutionStatus.SUCCESS.name());
        assertThat(byKey.get("output").getOutput()).contains("mock");
    }

    /**
     * Regression test for a race between the execution row and the node results.
     *
     * <p>The async worker inserts {@code node_executions} rows that reference the
     * {@code workflow_executions} row. If the worker starts before that row is committed,
     * the foreign key fails and the run is silently recorded as incomplete. A single run
     * can pass by luck, so this starts several at once to make the race reliable.
     */
    @Test
    @DisplayName("persists node results for concurrent runs without losing any")
    void persistsNodeResultsForConcurrentRuns() throws Exception {
        int runs = 5;
        List<UUID> workflowIds = new ArrayList<>();
        List<UUID> executionIds = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            workflowIds.add(persistChain("concurrent run " + i));
        }
        for (UUID workflowId : workflowIds) {
            executionIds.add(executionService.startAsync(workflowId).getId());
        }

        for (UUID executionId : executionIds) {
            ExecutionService.ExecutionSnapshot snapshot = null;
            for (int i = 0; i < 200; i++) {
                snapshot = executionService.snapshot(executionId);
                if (!"RUNNING".equals(snapshot.execution().getStatus())
                        && !"PENDING".equals(snapshot.execution().getStatus())) {
                    break;
                }
                Thread.sleep(25);
            }
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.execution().getStatus())
                    .as("run %s should succeed", executionId)
                    .isEqualTo(ExecutionStatus.SUCCESS.name());
            assertThat(snapshot.nodeExecutions())
                    .as("run %s should record all 4 nodes", executionId)
                    .hasSize(4);
        }
    }

    @Test
    @DisplayName("records a failed node and marks its descendants as skipped")
    void recordsFailureAndSkipsDescendants() {        UUID workflowId = persistChain("trigger a failure [[fail]]");

        var result = executionService.runSync(workflowId);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        var model = result.results().stream()
                .filter(r -> r.nodeKey().equals("model")).findFirst().orElseThrow();
        var output = result.results().stream()
                .filter(r -> r.nodeKey().equals("output")).findFirst().orElseThrow();

        assertThat(model.status()).isEqualTo(NodeExecutionStatus.FAILED);
        assertThat(model.error()).isNotBlank();
        assertThat(output.status()).isEqualTo(NodeExecutionStatus.SKIPPED);
    }

    @Test
    @DisplayName("exposes the run through the executions endpoint")
    void exposesExecutionOverHttp() throws Exception {
        UUID workflowId = persistChain("hello over http");

        String accepted = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/workflows/{id}/execute", workflowId))
                .andExpect(MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID executionId = UUID.fromString(objectMapper.readTree(accepted).get("executionId").asText());

        for (int i = 0; i < 100; i++) {
            String status = mockMvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/executions/{id}", executionId))
                    .andReturn().getResponse().getContentAsString();
            if (status.contains("\"SUCCESS\"")) {
                break;
            }
            Thread.sleep(50);
        }

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/executions/{id}", executionId))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCESS")))
                .andExpect(jsonPath("$.nodeExecutions", hasSize(4)));
    }

    @Test
    @DisplayName("lists the run history of a workflow")
    void listsExecutionHistory() throws Exception {
        UUID workflowId = persistChain("history");
        executionService.startAsync(workflowId);

        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/workflows/{id}/executions", workflowId))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

}
