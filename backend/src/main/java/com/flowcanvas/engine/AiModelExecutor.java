package com.flowcanvas.engine;

import com.flowcanvas.ai.AiProvider;
import com.flowcanvas.ai.AiProviderException;
import com.flowcanvas.ai.AiRequest;
import com.flowcanvas.ai.AiResponse;
import com.flowcanvas.domain.NodeType;
import com.flowcanvas.graph.GraphNode;
import org.springframework.stereotype.Component;

/**
 * Calls the configured {@link AiProvider}.
 *
 * <p>The incoming value becomes the user prompt. Optional config:
 * {@code {"model", "systemPrompt", "temperature"}}.
 *
 * <p>Provider errors are wrapped in {@link NodeExecutionException} so a failing model call
 * fails that node (and only its descendants) rather than aborting the whole run.
 */
@Component
public class AiModelExecutor implements NodeExecutor {

    private final AiProvider aiProvider;
    private final AiProviderConfig config;

    public AiModelExecutor(AiProvider aiProvider, AiProviderConfig config) {
        this.aiProvider = aiProvider;
        this.config = config;
    }

    @Override
    public NodeType type() {
        return NodeType.AI_MODEL;
    }

    @Override
    public String execute(NodeInput input) {
        GraphNode node = input.node();
        String model = node.configString("model", config.defaultModel());
        String systemPrompt = node.configString("systemPrompt", "");
        double temperature = node.configDouble("temperature", 0.7);
        String userPrompt = input.primaryInput();

        if (userPrompt.isBlank()) {
            throw new NodeExecutionException(
                    "AI Model node \"" + node.label() + "\" received an empty prompt.");
        }

        try {
            AiResponse response = aiProvider.complete(
                    new AiRequest(model, systemPrompt, userPrompt, temperature));
            return response.content();
        } catch (AiProviderException exception) {
            throw new NodeExecutionException(
                    "AI Model node \"" + node.label() + "\" failed: " + exception.getMessage(), exception);
        }
    }
}
