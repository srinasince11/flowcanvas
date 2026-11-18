package com.flowcanvas.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcanvas.domain.NodeType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GraphValidatorTest {

    private final GraphValidator validator = new GraphValidator();

    private GraphNode node(String key, NodeType type, Map<String, Object> config) {
        return new GraphNode(UUID.randomUUID(), key, type, key, 0, 0, config);
    }

    private GraphEdge edge(GraphNode from, GraphNode to) {
        return new GraphEdge(UUID.randomUUID(), from.id(), to.id(), "out", "in");
    }

    @Nested
    @DisplayName("cycle detection")
    class Cycles {

        @Test
        @DisplayName("detects a simple two-node cycle and names both nodes")
        void detectsSimpleCycle() {
            GraphNode a = node("a", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode b = node("b", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            WorkflowGraph graph = new WorkflowGraph(List.of(a, b), List.of(edge(a, b), edge(b, a)));

            GraphValidator.CycleResult result = validator.detectCycles(graph);

            assertThat(result.hasCycle()).isTrue();
            assertThat(result.cycle()).contains(a.id(), b.id());
        }

        @Test
        @DisplayName("detects a self-loop as a cycle")
        void detectsSelfLoop() {
            GraphNode a = node("a", NodeType.TEXT_INPUT, Map.of("text", "x"));
            WorkflowGraph graph = new WorkflowGraph(List.of(a), List.of(edge(a, a)));

            assertThat(validator.detectCycles(graph).hasCycle()).isTrue();
        }

        @Test
        @DisplayName("detects a cycle hidden behind a valid prefix")
        void detectsCycleBehindValidPrefix() {
            GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
            GraphNode a = node("a", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            GraphNode b = node("b", NodeType.AI_MODEL, Map.of("model", "m"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(input, a, b),
                    List.of(edge(input, a), edge(a, b), edge(b, a)));

            GraphValidator.CycleResult result = validator.detectCycles(graph);

            assertThat(result.hasCycle()).isTrue();
            assertThat(result.cycle()).contains(a.id(), b.id());
        }

        @Test
        @DisplayName("reports an acyclic graph as cycle-free")
        void acyclicGraphHasNoCycle() {
            GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
            GraphNode template = node("tpl", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            GraphNode output = node("out", NodeType.OUTPUT, Map.of("format", "text"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(input, template, output),
                    List.of(edge(input, template), edge(template, output)));

            assertThat(validator.detectCycles(graph).hasCycle()).isFalse();
        }

        @Test
        @DisplayName("reports a cycle as an error and names the nodes once each")
        void reportsCycleAsError() {
            GraphNode a = node("a", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode b = node("b", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            WorkflowGraph graph = new WorkflowGraph(List.of(a, b), List.of(edge(a, b), edge(b, a)));

            List<ValidationIssue> issues = validator.validate(graph);

            ValidationIssue cycleIssue = issues.stream()
                    .filter(i -> i.code().equals("CYCLE_DETECTED"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a CYCLE_DETECTED issue"));

            // The message closes the cycle on its starting node exactly once — no "a → a → a".
            // Assert on the cycle segment only: the surrounding sentence contains the
            // letter "a" in words like "contains".
            String cycleSegment = cycleIssue.message().substring(cycleIssue.message().indexOf(": ") + 2);
            assertThat(cycleSegment).isEqualTo("a → b → a.");
            assertThat(cycleSegment).doesNotContain("a → a");
        }

        @Test
        @DisplayName("names a three-node cycle in edge direction")
        void namesCycleInEdgeDirection() {
            GraphNode a = node("a", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            GraphNode b = node("b", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            GraphNode c = node("c", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(a, b, c), List.of(edge(a, b), edge(b, c), edge(c, a)));

            GraphValidator.CycleResult result = validator.detectCycles(graph);

            assertThat(result.cycle()).containsExactly(a.id(), b.id(), c.id(), a.id());
        }

        @Test
        @DisplayName("puts the cycle error first so the root cause is shown on top")
        void cycleErrorIsFirst() {
            GraphNode a = node("a", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode b = node("b", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            WorkflowGraph graph = new WorkflowGraph(List.of(a, b), List.of(edge(a, b), edge(b, a)));

            List<ValidationIssue> issues = validator.validate(graph);

            assertThat(issues).isNotEmpty();
            assertThat(issues.get(0).code()).isEqualTo("CYCLE_DETECTED");
        }

        @Test
        @DisplayName("a diamond is not a cycle")
        void diamondIsNotACycle() {
            GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "hello"));
            GraphNode left = node("left", NodeType.PROMPT_TEMPLATE, Map.of("template", "L {{input}}"));
            GraphNode right = node("right", NodeType.AI_MODEL, Map.of("model", "m"));
            GraphNode sink = node("out", NodeType.OUTPUT, Map.of("format", "text"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(input, left, right, sink),
                    List.of(edge(input, left), edge(input, right),
                            edge(left, sink), edge(right, sink)));

            assertThat(validator.detectCycles(graph).hasCycle()).isFalse();
        }
    }

    @Nested
    @DisplayName("connection validation")
    class Connections {

        @Test
        @DisplayName("rejects a Text Input node that has an incoming edge")
        void textInputCannotHaveInput() {
            GraphNode upstream = node("up", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode input = node("in", NodeType.TEXT_INPUT, Map.of("text", "y"));
            WorkflowGraph graph = new WorkflowGraph(List.of(upstream, input), List.of(edge(upstream, input)));

            List<ValidationIssue> issues = validator.validate(graph);

            assertThat(issues).anyMatch(i -> i.code().equals("TOO_MANY_INPUTS") && i.isError());
        }

        @Test
        @DisplayName("rejects an Output node that has an outgoing edge")
        void outputCannotHaveOutput() {
            GraphNode output = node("out", NodeType.OUTPUT, Map.of("format", "text"));
            GraphNode tail = node("tail", NodeType.OUTPUT, Map.of("format", "text"));
            WorkflowGraph graph = new WorkflowGraph(List.of(output, tail), List.of(edge(output, tail)));

            List<ValidationIssue> issues = validator.validate(graph);

            assertThat(issues).anyMatch(i -> i.code().equals("TOO_MANY_OUTPUTS") && i.isError());
        }

        @Test
        @DisplayName("rejects a node with a dangling edge endpoint")
        void rejectsDanglingEdge() {
            GraphNode a = node("a", NodeType.TEXT_INPUT, Map.of("text", "x"));
            UUID ghost = UUID.randomUUID();
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(a),
                    List.of(new GraphEdge(UUID.randomUUID(), a.id(), ghost, "out", "in")));

            List<ValidationIssue> issues = validator.validate(graph);

            assertThat(issues).anyMatch(i -> i.code().equals("EDGE_TARGET_MISSING"));
        }

        @Test
        @DisplayName("rejects a self-loop")
        void rejectsSelfLoop() {
            GraphNode a = node("a", NodeType.TEXT_INPUT, Map.of("text", "x"));
            WorkflowGraph graph = new WorkflowGraph(List.of(a), List.of(edge(a, a)));

            assertThat(validator.validate(graph)).anyMatch(i -> i.code().equals("SELF_LOOP"));
        }

        @Test
        @DisplayName("rejects duplicate connections between the same pair")
        void rejectsDuplicateEdges() {
            GraphNode a = node("a", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode b = node("b", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            WorkflowGraph graph = new WorkflowGraph(List.of(a, b), List.of(edge(a, b), edge(a, b)));

            assertThat(validator.validate(graph)).anyMatch(i -> i.code().equals("DUPLICATE_EDGE"));
        }

        @Test
        @DisplayName("rejects two nodes that share a key, case-insensitively")
        void rejectsDuplicateKeys() {
            GraphNode first = node("topic", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode second = node("Topic", NodeType.TEXT_INPUT, Map.of("text", "y"));
            WorkflowGraph graph = new WorkflowGraph(List.of(first, second), List.of());

            assertThat(validator.validate(graph))
                    .anyMatch(i -> i.code().equals("DUPLICATE_NODE_KEY") && i.isError());
        }

        @Test
        @DisplayName("rejects a key a template could not address")
        void rejectsMalformedKeys() {
            GraphNode spaced = node("has space", NodeType.TEXT_INPUT, Map.of("text", "x"));
            WorkflowGraph graph = new WorkflowGraph(List.of(spaced), List.of());

            assertThat(validator.validate(graph))
                    .anyMatch(i -> i.code().equals("INVALID_NODE_KEY") && i.isError());
        }

        @Test
        @DisplayName("allows a template to reference any ancestor, including through a join")
        void acceptsAncestorReferences() {
            GraphNode question = node("question", NodeType.TEXT_INPUT, Map.of("text", "q"));
            GraphNode context = node("context", NodeType.TEXT_INPUT, Map.of("text", "c"));
            GraphNode join = node("join", NodeType.PROMPT_TEMPLATE,
                    Map.of("template", "{{question}} / {{context}}"));
            GraphNode model = node("model", NodeType.AI_MODEL, Map.of("model", "m"));
            GraphNode summary = node("summary", NodeType.PROMPT_TEMPLATE,
                    Map.of("template", "Asked {{ QUESTION }}, got {{input}}"));
            GraphNode out = node("out", NodeType.OUTPUT, Map.of("format", "text"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(question, context, join, model, summary, out),
                    List.of(edge(question, join), edge(context, join), edge(join, model),
                            edge(model, summary), edge(summary, out)));

            assertThat(ValidationIssue.hasErrors(validator.validate(graph))).isFalse();
        }

        @Test
        @DisplayName("requires a Prompt Template node to have an input")
        void requiresPromptTemplateInput() {
            GraphNode template = node("tpl", NodeType.PROMPT_TEMPLATE, Map.of("template", "x"));
            WorkflowGraph graph = new WorkflowGraph(List.of(template), List.of());

            assertThat(validator.validate(graph)).anyMatch(i -> i.code().equals("MISSING_INPUT"));
        }

        @Test
        @DisplayName("allows an Output node to join several branches")
        void allowsFanInToOutput() {
            GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode left = node("left", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            GraphNode right = node("right", NodeType.AI_MODEL, Map.of("model", "m"));
            GraphNode sink = node("out", NodeType.OUTPUT, Map.of("format", "text"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(input, left, right, sink),
                    List.of(edge(input, left), edge(input, right),
                            edge(left, sink), edge(right, sink)));

            List<ValidationIssue> issues = validator.validate(graph);

            assertThat(issues).noneMatch(ValidationIssue::isError);
        }

        @Test
        @DisplayName("flags an empty graph as an error")
        void emptyGraphIsInvalid() {
            List<ValidationIssue> issues = validator.validate(new WorkflowGraph(List.of(), List.of()));

            assertThat(issues).anyMatch(i -> i.code().equals("GRAPH_EMPTY"));
        }

        @Test
        @DisplayName("warns but does not error when there is no Output node")
        void warnsWhenNoOutputNode() {
            GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode template = node("tpl", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            WorkflowGraph graph = new WorkflowGraph(List.of(input, template), List.of(edge(input, template)));

            List<ValidationIssue> issues = validator.validate(graph);

            assertThat(issues).noneMatch(ValidationIssue::isError);
            assertThat(issues).anyMatch(i -> i.code().equals("NO_OUTPUT_NODE") && !i.isError());
        }
    }

    @Nested
    @DisplayName("topological ordering")
    class Ordering {

        @Test
        @DisplayName("orders nodes so that producers come before consumers")
        void respectsDependencies() {
            GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode template = node("tpl", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            GraphNode model = node("model", NodeType.AI_MODEL, Map.of("model", "m"));
            GraphNode output = node("out", NodeType.OUTPUT, Map.of("format", "text"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(input, template, model, output),
                    List.of(edge(input, template), edge(template, model), edge(model, output)));

            List<GraphNode> ordered = validator.topologicalSort(graph);

            assertThat(ordered).extracting(GraphNode::key)
                    .containsExactly("input", "tpl", "model", "out");
        }

        @Test
        @DisplayName("puts a node after every one of its producers")
        void producerPrecedesConsumerInDiamond() {
            GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode left = node("left", NodeType.PROMPT_TEMPLATE, Map.of("template", "L"));
            GraphNode right = node("right", NodeType.AI_MODEL, Map.of("model", "m"));
            GraphNode sink = node("out", NodeType.OUTPUT, Map.of("format", "text"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(input, left, right, sink),
                    List.of(edge(input, left), edge(input, right),
                            edge(left, sink), edge(right, sink)));

            List<GraphNode> ordered = validator.topologicalSort(graph);
            List<String> keys = ordered.stream().map(GraphNode::key).toList();

            assertThat(keys.indexOf("input")).isLessThan(keys.indexOf("left"));
            assertThat(keys.indexOf("input")).isLessThan(keys.indexOf("right"));
            assertThat(keys.indexOf("left")).isLessThan(keys.indexOf("out"));
            assertThat(keys.indexOf("right")).isLessThan(keys.indexOf("out"));
        }

        @Test
        @DisplayName("is deterministic for the same graph")
        void isDeterministic() {
            GraphNode input = node("input", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode a = node("a", NodeType.PROMPT_TEMPLATE, Map.of("template", "A"));
            GraphNode b = node("b", NodeType.PROMPT_TEMPLATE, Map.of("template", "B"));
            GraphNode output = node("out", NodeType.OUTPUT, Map.of("format", "text"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(input, a, b, output),
                    List.of(edge(input, a), edge(input, b), edge(a, output), edge(b, output)));

            List<String> first = validator.topologicalSort(graph).stream().map(GraphNode::key).toList();
            List<String> second = validator.topologicalSort(graph).stream().map(GraphNode::key).toList();

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("breaks ties by node key, including for nodes that become ready mid-sort")
        void breaksTiesByKey() {
            // root fans out to c, a, b (inserted in that order). All three become ready at the
            // same moment, so the documented tie-break — ascending key — must decide.
            GraphNode root = node("root", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode c = node("c", NodeType.PROMPT_TEMPLATE, Map.of("template", "C"));
            GraphNode a = node("a", NodeType.PROMPT_TEMPLATE, Map.of("template", "A"));
            GraphNode b = node("b", NodeType.PROMPT_TEMPLATE, Map.of("template", "B"));
            WorkflowGraph graph = new WorkflowGraph(
                    List.of(root, c, a, b),
                    List.of(edge(root, c), edge(root, a), edge(root, b)));

            assertThat(validator.topologicalSort(graph)).extracting(GraphNode::key)
                    .containsExactly("root", "a", "b", "c");
        }

        @Test
        @DisplayName("refuses to order a graph containing a cycle")
        void throwsOnCycle() {
            GraphNode a = node("a", NodeType.TEXT_INPUT, Map.of("text", "x"));
            GraphNode b = node("b", NodeType.PROMPT_TEMPLATE, Map.of("template", "{{input}}"));
            WorkflowGraph graph = new WorkflowGraph(List.of(a, b), List.of(edge(a, b), edge(b, a)));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.topologicalSort(graph))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cycle");
        }
    }
}
