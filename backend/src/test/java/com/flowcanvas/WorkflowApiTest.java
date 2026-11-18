package com.flowcanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcanvas.api.dto.WorkflowDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST API integration tests: the full HTTP surface, exercised through {@code MockMvc}
 * against a real database.
 */
class WorkflowApiTest extends AbstractWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private WorkflowDtos.NodePayload node(String key, String type, String label, double x, double y,
                                         Map<String, Object> config) {
        return new WorkflowDtos.NodePayload(UUID.randomUUID(), key, type, label,
                new WorkflowDtos.PositionPayload(x, y), config);
    }

    private WorkflowDtos.EdgePayload edge(WorkflowDtos.NodePayload from, WorkflowDtos.NodePayload to) {
        return new WorkflowDtos.EdgePayload(UUID.randomUUID(), from.id(), to.id(), "out", "in");
    }

    @Test
    @DisplayName("creates a workflow and returns it with the full graph")
    void createsWorkflow() throws Exception {
        var input = node("input", "TEXT_INPUT", "Input", 0, 0, Map.of("text", "hi"));
        var output = node("output", "OUTPUT", "Output", 200, 0, Map.of("format", "text"));

        var request = new WorkflowDtos.WorkflowRequest(
                "API workflow", "created via API", "DRAFT",
                List.of(input, output), List.of(edge(input, output)));

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("API workflow")))
                .andExpect(jsonPath("$.nodes", hasSize(2)))
                .andExpect(jsonPath("$.edges", hasSize(1)))
                .andExpect(jsonPath("$.nodes[0].config.text", is("hi")));
    }

    @Test
    @DisplayName("refuses to store duplicate node keys, and creates nothing")
    void rejectsUnstorableGraphOnCreate() throws Exception {
        String name = "Dup keys " + UUID.randomUUID();
        var first = node("same", "TEXT_INPUT", "First", 0, 0, Map.of("text", "a"));
        var second = node("same", "TEXT_INPUT", "Second", 0, 100, Map.of("text", "b"));
        var request = new WorkflowDtos.WorkflowRequest(name, null, "DRAFT", List.of(first, second), List.of());

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("GRAPH_NOT_STORABLE")))
                .andExpect(jsonPath("$.issues[0].code", is("DUPLICATE_NODE_KEY")));

        // Atomic: the metadata was not committed on its own.
        mockMvc.perform(get("/api/v1/workflows").param("search", name))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    @DisplayName("rolls back a rename when the new graph cannot be stored")
    void updateIsAtomic() throws Exception {
        var input = node("input", "TEXT_INPUT", "Input", 0, 0, Map.of("text", "hi"));
        var created = new WorkflowDtos.WorkflowRequest("Original name", null, "DRAFT", List.of(input), List.of());
        String body = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(created)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        var clash = node("input", "TEXT_INPUT", "Clash", 0, 100, Map.of("text", "x"));
        var renamed = new WorkflowDtos.WorkflowRequest("Renamed", null, "DRAFT", List.of(input, clash), List.of());
        mockMvc.perform(put("/api/v1/workflows/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(renamed)))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(jsonPath("$.name", is("Original name")))
                .andExpect(jsonPath("$.nodes", hasSize(1)));
    }

    @Test
    @DisplayName("treats LIKE wildcards in a search term literally")
    void searchEscapesWildcards() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        for (String name : List.of(marker + " 100% done", marker + " 1000 done")) {
            var request = new WorkflowDtos.WorkflowRequest(name, null, "DRAFT", List.of(), List.of());
            mockMvc.perform(post("/api/v1/workflows")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(request)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/workflows").param("search", marker + " 100%"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name", is(marker + " 100% done")));
    }

    @Test
    @DisplayName("rejects a workflow without a name")
    void rejectsInvalidWorkflow() throws Exception {
        var request = new WorkflowDtos.WorkflowRequest("", "no name", "DRAFT", List.of(), List.of());

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("lists, reads, updates and deletes a workflow")
    void fullCrudLifecycle() throws Exception {
        var input = node("input", "TEXT_INPUT", "Input", 0, 0, Map.of("text", "hi"));
        var request = new WorkflowDtos.WorkflowRequest("CRUD", "v1", "DRAFT",
                List.of(input), List.of());

        String createResponse = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        mockMvc.perform(get("/api/v1/workflows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("CRUD")))
                .andExpect(jsonPath("$.nodes", hasSize(1)));

        mockMvc.perform(get("/api/v1/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", notNullValue()))
                .andExpect(jsonPath("$.totalItems", notNullValue()));

        var update = new WorkflowDtos.WorkflowRequest("CRUD renamed", "v2", "PUBLISHED",
                List.of(input), List.of());
        mockMvc.perform(put("/api/v1/workflows/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("CRUD renamed")))
                .andExpect(jsonPath("$.status", is("PUBLISHED")));

        mockMvc.perform(delete("/api/v1/workflows/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/workflows/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("WORKFLOW_NOT_FOUND")));
    }

    @Test
    @DisplayName("validates a graph without saving it and reports a cycle")
    void validatesGraphWithCycle() throws Exception {
        var a = node("a", "TEXT_INPUT", "A", 0, 0, Map.of("text", "x"));
        var b = node("b", "PROMPT_TEMPLATE", "B", 200, 0, Map.of("template", "{{input}}"));

        var request = new WorkflowDtos.ValidateRequest(List.of(a, b), List.of(edge(a, b), edge(b, a)));

        mockMvc.perform(post("/api/v1/workflows/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(false)))
                .andExpect(jsonPath("$.issues[0].code", is("CYCLE_DETECTED")));
    }

    @Test
    @DisplayName("validates a legal graph and returns the execution order")
    void validatesLegalGraph() throws Exception {
        var input = node("input", "TEXT_INPUT", "Input", 0, 0, Map.of("text", "x"));
        var template = node("template", "PROMPT_TEMPLATE", "Template", 200, 0, Map.of("template", "{{input}}"));
        var output = node("output", "OUTPUT", "Output", 400, 0, Map.of("format", "text"));

        var request = new WorkflowDtos.ValidateRequest(
                List.of(input, template, output),
                List.of(edge(input, template), edge(template, output)));

        mockMvc.perform(post("/api/v1/workflows/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.executionOrder", hasSize(3)))
                .andExpect(jsonPath("$.executionOrder[0].nodeKey", is("input")))
                .andExpect(jsonPath("$.executionOrder[1].nodeKey", is("template")))
                .andExpect(jsonPath("$.executionOrder[2].nodeKey", is("output")));
    }

    @Test
    @DisplayName("reports an illegal connection as a validation error")
    void reportsIllegalConnection() throws Exception {
        var a = node("a", "TEXT_INPUT", "A", 0, 0, Map.of("text", "x"));
        var b = node("b", "TEXT_INPUT", "B", 200, 0, Map.of("text", "y"));

        var request = new WorkflowDtos.ValidateRequest(List.of(a, b), List.of(edge(a, b)));

        mockMvc.perform(post("/api/v1/workflows/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(false)))
                .andExpect(jsonPath("$.issues[0].code", is("TOO_MANY_INPUTS")));
    }

    @Test
    @DisplayName("serves the built-in example workflows")
    void servesExamples() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/examples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].name", notNullValue()))
                .andExpect(jsonPath("$[0].nodes", notNullValue()));
    }

    @Test
    @DisplayName("exposes the health endpoint")
    void exposesHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }
}
