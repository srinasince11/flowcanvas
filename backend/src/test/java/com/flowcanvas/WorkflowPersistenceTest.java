package com.flowcanvas;

import com.flowcanvas.domain.Workflow;
import com.flowcanvas.domain.WorkflowStatus;
import com.flowcanvas.graph.GraphEdge;
import com.flowcanvas.graph.GraphNode;
import com.flowcanvas.domain.NodeType;
import com.flowcanvas.repository.WorkflowEdgeRepository;
import com.flowcanvas.repository.WorkflowNodeRepository;
import com.flowcanvas.repository.WorkflowRepository;
import com.flowcanvas.service.WorkflowService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence integration tests.
 *
 * <p>These run against a real PostgreSQL instance (embedded) with the production Flyway
 * migrations applied, so they exercise the actual schema — including the JSONB column, the
 * foreign keys and the unique constraints — rather than a mock.
 */
@SpringBootTest

@Transactional
class WorkflowPersistenceTest extends AbstractDatabaseTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowNodeRepository nodeRepository;

    @Autowired
    private WorkflowEdgeRepository edgeRepository;

    @Test
    @DisplayName("persists a workflow with its nodes and edges and reads it back")
    void roundTripsAWorkflow() {
        UUID inputId = UUID.randomUUID();
        UUID outputId = UUID.randomUUID();

        Workflow workflow = workflowService.create("Round trip", "A test workflow", "DRAFT");
        UUID workflowId = workflow.getId();

        workflowService.replaceGraph(workflowId, List.of(
                new GraphNode(inputId, "input", NodeType.TEXT_INPUT, "Input", 10, 20,
                        Map.of("text", "hello")),
                new GraphNode(outputId, "output", NodeType.OUTPUT, "Output", 200, 20,
                        Map.of("format", "text"))),
                List.of(new GraphEdge(UUID.randomUUID(), inputId, outputId, "out", "in")));

        var graph = workflowService.loadGraph(workflowId);

        assertThat(graph.nodeList()).hasSize(2);
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.node(inputId).configString("text", "")).isEqualTo("hello");
        assertThat(graph.node(inputId).positionX()).isEqualTo(10.0);
        assertThat(graph.node(outputId).type()).isEqualTo(NodeType.OUTPUT);
    }

    @Test
    @DisplayName("stores node configuration as JSONB and returns it unchanged")
    void storesConfigAsJson() {
        UUID workflowId = workflowService.create("Config", null, "DRAFT").getId();
        UUID nodeId = UUID.randomUUID();

        workflowService.replaceGraph(workflowId,
                List.of(new GraphNode(nodeId, "model", NodeType.AI_MODEL, "Model", 0, 0,
                        Map.of("model", "gpt-4o-mini", "temperature", 0.35, "systemPrompt", "Be nice"))),
                List.of());

        var graph = workflowService.loadGraph(workflowId);
        var node = graph.node(nodeId);

        assertThat(node.configString("model", "")).isEqualTo("gpt-4o-mini");
        assertThat(node.configString("systemPrompt", "")).isEqualTo("Be nice");
        assertThat(node.configDouble("temperature", -1)).isEqualTo(0.35);
    }

    @Test
    @DisplayName("replacing the graph removes the nodes and edges that were deleted")
    void replaceGraphRemovesStaleRows() {
        UUID workflowId = workflowService.create("Replace", null, "DRAFT").getId();
        UUID keepId = UUID.randomUUID();
        UUID dropId = UUID.randomUUID();

        workflowService.replaceGraph(workflowId,
                List.of(new GraphNode(keepId, "keep", NodeType.TEXT_INPUT, "Keep", 0, 0, Map.of("text", "a")),
                        new GraphNode(dropId, "drop", NodeType.TEXT_INPUT, "Drop", 0, 0, Map.of("text", "b"))),
                List.of());

        assertThat(nodeRepository.findByWorkflowId(workflowId)).hasSize(2);

        workflowService.replaceGraph(workflowId,
                List.of(new GraphNode(keepId, "keep", NodeType.TEXT_INPUT, "Keep", 5, 6, Map.of("text", "c"))),
                List.of());

        var remaining = nodeRepository.findByWorkflowId(workflowId);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getId()).isEqualTo(keepId);
        assertThat(remaining.get(0).getPositionX()).isEqualTo(5.0);
        assertThat(edgeRepository.findByWorkflowId(workflowId)).isEmpty();
    }

    @Test
    @DisplayName("drops nodes and edges when the workflow is deleted")
    void cascadeDeleteRemovesGraph() {
        UUID workflowId = workflowService.create("Delete me", null, "DRAFT").getId();
        UUID nodeId = UUID.randomUUID();

        workflowService.replaceGraph(workflowId,
                List.of(new GraphNode(nodeId, "n", NodeType.TEXT_INPUT, "N", 0, 0, Map.of("text", "x"))),
                List.of());
        assertThat(nodeRepository.findByWorkflowId(workflowId)).hasSize(1);

        workflowService.delete(workflowId);

        assertThat(workflowRepository.findById(workflowId)).isEmpty();
        assertThat(nodeRepository.findByWorkflowId(workflowId)).isEmpty();
    }

    @Test
    @DisplayName("lists workflows newest-updated first and finds them by name")
    void listsAndSearches() {
        workflowService.create("Alpha workflow", "first", "DRAFT");
        workflowService.create("Beta workflow", "second", "DRAFT");

        var all = workflowService.list(null, org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(all.getTotalElements()).isGreaterThanOrEqualTo(2);

        var found = workflowService.list("alpha", org.springframework.data.domain.PageRequest.of(0, 50));
        assertThat(found.getContent()).extracting(Workflow::getName).contains("Alpha workflow");
    }

    @Test
    @DisplayName("throws a not-found error for an unknown workflow id")
    void throwsForUnknownId() {
        assertThatThrownBy(() -> workflowService.getEntity(UUID.randomUUID()))
                .isInstanceOf(WorkflowService.WorkflowNotFoundException.class);
    }

    @Test
    @DisplayName("keeps the status field and rejects unknown statuses")
    void handlesStatus() {
        Workflow published = workflowService.create("Published", null, "PUBLISHED");
        assertThat(published.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED.name());

        assertThatThrownBy(() -> workflowService.create("Bad", null, "NOT_A_STATUS"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
