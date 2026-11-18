package com.flowcanvas.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcanvas.ai.MockAiProvider;
import com.flowcanvas.config.AiProperties;
import com.flowcanvas.domain.ExecutionStatus;
import com.flowcanvas.engine.AiModelExecutor;
import com.flowcanvas.engine.AiProviderConfig;
import com.flowcanvas.engine.ExecutionResult;
import com.flowcanvas.engine.NodeExecutionResult;
import com.flowcanvas.engine.OutputExecutor;
import com.flowcanvas.engine.PromptTemplateExecutor;
import com.flowcanvas.engine.TextInputExecutor;
import com.flowcanvas.engine.WorkflowEngine;
import com.flowcanvas.graph.GraphValidator;
import com.flowcanvas.graph.ValidationIssue;
import com.flowcanvas.graph.WorkflowGraph;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The examples are the first thing a visitor runs, so every one must be valid and succeed. */
class ExampleWorkflowsTest {

    private static final GraphValidator VALIDATOR = new GraphValidator();
    private static final WorkflowEngine ENGINE = new WorkflowEngine(VALIDATOR, List.of(
            new TextInputExecutor(),
            new PromptTemplateExecutor(),
            new AiModelExecutor(new MockAiProvider(),
                    new AiProviderConfig(new AiProperties("mock", null, null, null, 0, 0))),
            new OutputExecutor()));

    static List<ExampleWorkflowService.Example> examples() {
        return new ExampleWorkflowService(null).examples();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void exampleIsValidAndRunsSuccessfully(ExampleWorkflowService.Example example) {
        WorkflowGraph graph = new WorkflowGraph(example.nodes(), example.edges());

        assertThat(VALIDATOR.validate(graph)).as("validation issues").isEmpty();

        ExecutionResult result = ENGINE.execute(graph);
        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.results()).hasSize(example.nodes().size());
        assertThat(result.results()).extracting(NodeExecutionResult::output).doesNotContainNull();
        assertThat(ValidationIssue.hasErrors(result.issues())).isFalse();
    }
}
