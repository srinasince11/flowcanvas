package com.flowcanvas.engine;

import com.flowcanvas.domain.NodeType;
import com.flowcanvas.graph.TemplateVariables;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Renders a prompt template.
 *
 * <p>Placeholders:
 * <ul>
 *   <li>{@code {{input}}} — the value of the node's direct incoming connection.</li>
 *   <li>{@code {{nodeKey}}} — the output of any upstream node, addressed by its key.</li>
 * </ul>
 *
 * <p>Unknown placeholders are an <b>error</b>, not an empty string. A template that silently
 * renders to nothing is the classic way a workflow "succeeds" while producing nonsense, so we
 * fail loudly instead.
 *
 * <p>Config: {@code {"template": "Summarise: {{input}}"}}.
 */
@Component
public class PromptTemplateExecutor implements NodeExecutor {

    private static final Pattern PLACEHOLDER = TemplateVariables.PLACEHOLDER;

    @Override
    public NodeType type() {
        return NodeType.PROMPT_TEMPLATE;
    }

    @Override
    public String execute(NodeInput input) {
        String template = input.node().configString("template", "");
        if (template.isBlank()) {
            throw new NodeExecutionException("Prompt template \"" + input.node().label() + "\" is empty.");
        }

        Map<String, String> variables = input.variables();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = resolve(name, input, variables);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolve(String name, NodeInput input, Map<String, String> variables) {
        if (TemplateVariables.isInput(name)) {
            return input.primaryInput();
        }
        String value = variables.get(name);
        if (value == null) {
            // Case-insensitive fallback so {{userName}} matches a node keyed "username".
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            throw new NodeExecutionException(
                    "Unknown template variable \"" + name + "\" in prompt template \""
                            + input.node().label() + "\". Available: " + String.join(", ", variables.keySet()));
        }
        return value;
    }
}
