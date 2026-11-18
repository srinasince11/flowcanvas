package com.flowcanvas.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcanvas.ai.AiProvider;
import com.flowcanvas.ai.AiRequest;
import com.flowcanvas.ai.AiResponse;
import com.flowcanvas.config.AiProperties;
import com.flowcanvas.domain.ExecutionStatus;
import com.flowcanvas.domain.NodeExecutionStatus;
import com.flowcanvas.domain.NodeType;
import com.flowcanvas.graph.GraphEdge;
import com.flowcanvas.graph.GraphNode;
import com.flowcanvas.graph.GraphValidator;
import com.flowcanvas.graph.WorkflowGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkflowEngineTest {

    private WorkflowEngine engine;
    private RecordingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RecordingProvider();
        GraphValidator validator = new GraphValidator();
        AiProviderConfig config = new AiProviderConfig(
                new AiProperties("mock", "http://localhost", "", "test-model", 30, 0));
        engine = new WorkflowEngine(validator, List.of(
                new TextInputExecutor(),
                new PromptTemplateExecutor(),
                new AiModelExecutor(provider, config),
                new OutputExecutor()));
    }

    // --- helpers ---------------------------------------------------------------------

    private GraphNode node(String key, NodeType type, Map<String, Object> config) {
        return new GraphNode(UUID.randomUUID(), key, type, key, 0, 0, config);
    }

    private GraphEdge edge(GraphNode from, GraphNode to) {
        return new GraphEdge(UUID.randomUUID(), from.id(), to.id(), "out", "in");
    }

    private NodeExecutionResult resultFor(ExecutionResult result, String key) {
        return result.results().stream()
                .filter(r -> r.nodeKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result for node " + key));
    }

    // --- tests -----------------------------------------------------------------------

    @Test
    @DisplayName("executes a linear chain in dependency order")
    void executesInDependencyOrder() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello world"));
        GraphNode template = node("template", NodeType.PROMPT_TEMPLATE,
                Map.of("template", "Summarise: {{input}}"));
        GraphNode model = node("model", NodeType.AI_MODEL, Map.of("model", "m"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));

        WorkflowGraph graph = new WorkflowGraph(
                List.of(input, template, model, output),
                List.of(edge(input, template), edge(template, model), edge(model, output)));

        ExecutionResult result = engine.execute(graph);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.results()).extracting(NodeExecutionResult::nodeKey)
                .containsExactly("input", "template", "model", "output");
        assertThat(result.results()).extracting(NodeExecutionResult::sequence)
                .containsExactly(1, 2, 3, 4);
        assertThat(resultFor(result, "template").output()).isEqualTo("Summarise: hello world");
        assertThat(resultFor(result, "input").output()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("passes the previous node's output into the next node")
    void threadsValuesThroughTheChain() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "seed"));
        GraphNode template = node("template", NodeType.PROMPT_TEMPLATE,
                Map.of("template", "A {{input}}"));
        GraphNode second = node("second", NodeType.PROMPT_TEMPLATE,
                Map.of("template", "B {{input}}"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));

        WorkflowGraph graph = new WorkflowGraph(
                List.of(input, template, second, output),
                List.of(edge(input, template), edge(template, second), edge(second, output)));

        ExecutionResult result = engine.execute(graph);

        assertThat(resultFor(result, "second").output()).isEqualTo("B A seed");
        assertThat(resultFor(result, "output").output()).isEqualTo("B A seed");
    }

    @Test
    @DisplayName("resolves {{nodeKey}} against upstream ancestors, not just the direct parent")
    void resolvesAncestorVariables() {
        GraphNode source = node("source", NodeType.TEXT_INPUT, Map.of("text", "TOPIC"));
        GraphNode branch = node("branch", NodeType.PROMPT_TEMPLATE, Map.of("template", "unused"));
        GraphNode consumer = node("consumer", NodeType.PROMPT_TEMPLATE,
                Map.of("template", "Topic is {{source}}"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));

        WorkflowGraph graph = new WorkflowGraph(
                List.of(source, branch, consumer, output),
                List.of(edge(source, branch), edge(branch, consumer), edge(consumer, output)));

        ExecutionResult result = engine.execute(graph);

        assertThat(resultFor(result, "consumer").output()).isEqualTo("Topic is TOPIC");
    }

    @Test
    @DisplayName("does not let a template read a node from an unrelated branch")
    void doesNotResolveNonAncestors() {
        // "aSibling" sorts before "zConsumer" and therefore has already run when the
        // consumer executes — but it is not an ancestor, so it must not be visible.
        GraphNode sibling = node("aSibling", NodeType.TEXT_INPUT, Map.of("text", "LEAK"));
        GraphNode source = node("source", NodeType.TEXT_INPUT, Map.of("text", "ok"));
        GraphNode consumer = node("zConsumer", NodeType.PROMPT_TEMPLATE,
                Map.of("template", "{{input}} {{aSibling}}"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));

        WorkflowGraph graph = new WorkflowGraph(
                List.of(sibling, source, consumer, output),
                List.of(edge(source, consumer), edge(consumer, output), edge(sibling, output)));

        ExecutionResult result = engine.execute(graph);

        assertThat(result.results()).isEmpty();
        assertThat(result.issues()).anyMatch(i -> i.code().equals("UNKNOWN_TEMPLATE_VARIABLE")
                && i.message().contains("aSibling"));
    }

    @Test
    @DisplayName("marks a failing node as FAILED and its descendants as SKIPPED")
    void isolatesFailures() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
        GraphNode model = node("model", NodeType.AI_MODEL, Map.of("model", "m"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));
        provider.failNext = true;

        WorkflowGraph graph = new WorkflowGraph(
                List.of(input, model, output),
                List.of(edge(input, model), edge(model, output)));

        ExecutionResult result = engine.execute(graph);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(resultFor(result, "input").status()).isEqualTo(NodeExecutionStatus.SUCCESS);
        assertThat(resultFor(result, "model").status()).isEqualTo(NodeExecutionStatus.FAILED);
        assertThat(resultFor(result, "model").error()).isNotBlank();
        assertThat(resultFor(result, "output").status()).isEqualTo(NodeExecutionStatus.SKIPPED);
    }

    @Test
    @DisplayName("keeps running independent branches after one branch fails")
    void continuesIndependentBranches() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
        GraphNode failing = node("failing", NodeType.AI_MODEL, Map.of("model", "m"));
        GraphNode healthy = node("healthy", NodeType.PROMPT_TEMPLATE,
                Map.of("template", "OK {{input}}"));
        GraphNode failedSink = node("failedSink", NodeType.OUTPUT, Map.of("format", "text"));
        GraphNode healthySink = node("healthySink", NodeType.OUTPUT, Map.of("format", "text"));
        provider.failNext = true;

        WorkflowGraph graph = new WorkflowGraph(
                List.of(input, failing, healthy, failedSink, healthySink),
                List.of(edge(input, failing), edge(input, healthy),
                        edge(failing, failedSink), edge(healthy, healthySink)));

        ExecutionResult result = engine.execute(graph);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(resultFor(result, "failing").status()).isEqualTo(NodeExecutionStatus.FAILED);
        assertThat(resultFor(result, "failedSink").status()).isEqualTo(NodeExecutionStatus.SKIPPED);
        assertThat(resultFor(result, "healthy").status()).isEqualTo(NodeExecutionStatus.SUCCESS);
        assertThat(resultFor(result, "healthySink").status()).isEqualTo(NodeExecutionStatus.SUCCESS);
        assertThat(resultFor(result, "healthySink").output()).isEqualTo("OK hello");
    }

    @Test
    @DisplayName("refuses to run a graph that contains a cycle")
    void rejectsCyclicGraph() {
        GraphNode a = node("a", NodeType.TEXT_INPUT, Map.of("text", "x"));
        GraphNode b = node("b", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
        WorkflowGraph graph = new WorkflowGraph(List.of(a, b), List.of(edge(a, b), edge(b, a)));

        ExecutionResult result = engine.execute(graph);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.results()).isEmpty();
        assertThat(result.issues()).anyMatch(i -> i.code().equals("CYCLE_DETECTED"));
    }

    @Test
    @DisplayName("refuses to run a graph with an illegal connection")
    void rejectsInvalidConnections() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "x"));
        GraphNode other = node("other", NodeType.TEXT_INPUT, Map.of("text", "y"));
        WorkflowGraph graph = new WorkflowGraph(List.of(input, other), List.of(edge(input, other)));

        ExecutionResult result = engine.execute(graph);

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.issues()).isNotEmpty();
    }

    @Test
    @DisplayName("reports a duration for every executed node")
    void reportsDurations() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));
        WorkflowGraph graph = new WorkflowGraph(List.of(input, output), List.of(edge(input, output)));

        ExecutionResult result = engine.execute(graph);

        assertThat(result.results()).allSatisfy(r -> assertThat(r.durationMs()).isGreaterThanOrEqualTo(0L));
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("refuses to start when a template references an unknown variable")
    void rejectsUnknownTemplateVariable() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "x"));
        GraphNode template = node("template", NodeType.PROMPT_TEMPLATE,
                Map.of("template", "{{nonexistent}}"));
        GraphNode model = node("model", NodeType.AI_MODEL, Map.of("model", "m"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));
        WorkflowGraph graph = new WorkflowGraph(
                List.of(input, template, model, output),
                List.of(edge(input, template), edge(template, model), edge(model, output)));

        ExecutionResult result = engine.execute(graph);

        // Caught statically: nothing runs, so no model call is wasted on a broken workflow.
        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.results()).isEmpty();
        assertThat(result.issues()).anyMatch(i -> i.code().equals("UNKNOWN_TEMPLATE_VARIABLE")
                && i.message().contains("nonexistent"));
        assertThat(provider.calls.get()).isZero();
    }

    @Test
    @DisplayName("the template executor itself refuses a variable it was not given")
    void executorRejectsUnknownVariable() {
        GraphNode template = node("template", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{ghost}}"));
        NodeInput nodeInput = new NodeInput(template, Map.of("input", "x"), Map.of("input", "x"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new PromptTemplateExecutor().execute(nodeInput))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("a prompt template can join several branches by key")
    void templateJoinsBranchesByKey() {
        GraphNode question = node("question", NodeType.TEXT_INPUT, Map.of("text", "Why?"));
        GraphNode context = node("context", NodeType.TEXT_INPUT, Map.of("text", "Because."));
        GraphNode prompt = node("prompt", NodeType.PROMPT_TEMPLATE,
                Map.of("template", "Q={{question}} C={{context}} ALL={{input}}"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));
        WorkflowGraph graph = new WorkflowGraph(
                List.of(question, context, prompt, output),
                List.of(edge(question, prompt), edge(context, prompt), edge(prompt, output)));

        ExecutionResult result = engine.execute(graph);

        // {{input}} joins every direct input in key order: "context" before "question".
        assertThat(resultFor(result, "prompt").output())
                .isEqualTo("Q=Why? C=Because. ALL=Because.\n\nWhy?");
    }

    @Test
    @DisplayName("reports every lifecycle event to the listener, in order")
    void notifiesListener() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
        GraphNode model = node("model", NodeType.AI_MODEL, Map.of("model", "m"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));
        provider.failNext = true;
        WorkflowGraph graph = new WorkflowGraph(
                List.of(input, model, output),
                List.of(edge(input, model), edge(model, output)));

        List<String> events = new ArrayList<>();
        engine.execute(graph, new ExecutionListener() {
            @Override
            public void onPlanned(List<GraphNode> ordered) {
                events.add("planned:" + ordered.size());
            }

            @Override
            public void onNodeStarted(GraphNode node, int sequence, java.time.Instant startedAt) {
                events.add("start:" + node.key());
            }

            @Override
            public void onNodeFinished(NodeExecutionResult result) {
                events.add(result.status() + ":" + result.nodeKey());
            }
        });

        // A skipped node is reported as finished without ever being started.
        assertThat(events).containsExactly(
                "planned:3",
                "start:input", "SUCCESS:input",
                "start:model", "FAILED:model",
                "SKIPPED:output");
    }

    @Test
    @DisplayName("calls the AI provider exactly once per AI node")
    void invokesProviderOncePerAiNode() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
        GraphNode model = node("model", NodeType.AI_MODEL, Map.of("model", "m"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));
        WorkflowGraph graph = new WorkflowGraph(
                List.of(input, model, output),
                List.of(edge(input, model), edge(model, output)));

        engine.execute(graph);

        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(provider.lastRequest.userPrompt()).isEqualTo("hello");
    }

    @Test
    @DisplayName("emits JSON at an Output node configured for json")
    void supportsJsonOutput() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "json"));
        WorkflowGraph graph = new WorkflowGraph(List.of(input, output), List.of(edge(input, output)));

        ExecutionResult result = engine.execute(graph);

        assertThat(resultFor(result, "output").output()).contains("\"input\"").contains("hello");
    }

    @Test
    @DisplayName("escapes control characters in JSON output")
    void jsonOutputIsAlwaysValidJson() throws Exception {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "tab\tquote\"bell\u0007"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "json"));
        WorkflowGraph graph = new WorkflowGraph(List.of(input, output), List.of(edge(input, output)));

        String json = resultFor(engine.execute(graph), "output").output();

        Map<?, ?> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        assertThat(parsed.get("input")).isEqualTo("tab\tquote\"bell\u0007");
    }

    @Test
    @DisplayName("joins multiple branches at an Output node")
    void joinsBranchesAtOutput() {
        GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
        GraphNode left = node("left", NodeType.PROMPT_TEMPLATE, Map.of("template", "L {{input}}"));
        GraphNode right = node("right", NodeType.PROMPT_TEMPLATE, Map.of("template", "R {{input}}"));
        GraphNode output = node("output", NodeType.OUTPUT, Map.of("format", "text"));
        WorkflowGraph graph = new WorkflowGraph(
                List.of(input, left, right, output),
                List.of(edge(input, left), edge(input, right),
                        edge(left, output), edge(right, output)));

        ExecutionResult result = engine.execute(graph);

        String joined = resultFor(result, "output").output();
        assertThat(joined).contains("L hello");
        assertThat(joined).contains("R hello");
    }

    /** A provider that records its calls and can be told to fail. */
    static final class RecordingProvider implements AiProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failNext = false;
        volatile AiRequest lastRequest;

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public AiResponse complete(AiRequest request) {
            calls.incrementAndGet();
            lastRequest = request;
            if (failNext) {
                failNext = false;
                throw new com.flowcanvas.ai.AiProviderException("provider exploded");
            }
            return new AiResponse("AI<" + request.userPrompt() + ">", request.model(), name(), 1L);
        }
    }
}
