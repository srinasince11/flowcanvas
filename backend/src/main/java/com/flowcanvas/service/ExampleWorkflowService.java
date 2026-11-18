package com.flowcanvas.service;

import com.flowcanvas.domain.Workflow;
import com.flowcanvas.graph.GraphEdge;
import com.flowcanvas.graph.GraphNode;
import com.flowcanvas.graph.WorkflowGraph;
import com.flowcanvas.domain.NodeType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ships ready-made example workflows so a first-time user opens the app to something real
 * instead of an empty canvas.
 *
 * <p>Seeding is idempotent: it only runs when the workflow table is empty, so restarting the
 * backend never duplicates the examples.
 */
@Service
public class ExampleWorkflowService {

    private final WorkflowService workflowService;

    public ExampleWorkflowService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** Creates the examples if no workflows exist yet. */
    @Transactional
    public void seedIfEmpty() {
        if (workflowService.list(null, org.springframework.data.domain.PageRequest.of(0, 1))
                .getTotalElements() > 0) {
            return;
        }
        for (Example example : examples()) {
            Workflow workflow = workflowService.create(example.name(), example.description(), "PUBLISHED");
            workflowService.replaceGraph(workflow.getId(), example.nodes(), example.edges());
        }
    }

    /** The catalogue of built-in examples, also exposed by {@code GET /api/v1/workflows/examples}. */
    public List<Example> examples() {
        return List.of(
                researchBrief(),
                customerSupport(),
                codeReview(),
                contentPipeline());
    }

    // --- 0. Fan-out / fan-in research brief -----------------------------------------
    /**
     * The example that exercises the engine properly: one input fans out into two
     * independent branches, and a template joins them back together by key. It shows
     * dependency ordering (the join waits for both branches), {@code {{nodeKey}}} variables
     * reaching across branches through a common descendant, and failure isolation (put
     * {@code [[fail]]} into one branch's prompt and only that branch fails).
     */
    private Example researchBrief() {
        UUID topic = UUID.randomUUID();
        UUID prosPrompt = UUID.randomUUID();
        UUID consPrompt = UUID.randomUUID();
        UUID prosModel = UUID.randomUUID();
        UUID consModel = UUID.randomUUID();
        UUID synthesis = UUID.randomUUID();
        UUID briefModel = UUID.randomUUID();
        UUID brief = UUID.randomUUID();

        List<GraphNode> nodes = List.of(
                new GraphNode(topic, "topic", NodeType.TEXT_INPUT, "Topic", 40, 220,
                        java.util.Map.of("text", "Should a 10-person startup adopt a monorepo?")),
                new GraphNode(prosPrompt, "prosPrompt", NodeType.PROMPT_TEMPLATE, "Case For", 330, 80,
                        java.util.Map.of("template", "List the three strongest arguments FOR: {{input}}")),
                new GraphNode(consPrompt, "consPrompt", NodeType.PROMPT_TEMPLATE, "Case Against", 330, 360,
                        java.util.Map.of("template", "List the three strongest arguments AGAINST: {{input}}")),
                new GraphNode(prosModel, "advocate", NodeType.AI_MODEL, "Advocate", 620, 80,
                        java.util.Map.of("model", "gpt-4o-mini",
                                "systemPrompt", "You argue persuasively and concretely.", "temperature", 0.7)),
                new GraphNode(consModel, "critic", NodeType.AI_MODEL, "Critic", 620, 360,
                        java.util.Map.of("model", "gpt-4o-mini",
                                "systemPrompt", "You are a sceptical, evidence-driven reviewer.", "temperature", 0.7)),
                new GraphNode(synthesis, "synthesisPrompt", NodeType.PROMPT_TEMPLATE, "Synthesis Prompt", 910, 220,
                        java.util.Map.of("template",
                                "Question: {{topic}}\n\nArguments for:\n{{advocate}}\n\n"
                                        + "Arguments against:\n{{critic}}\n\n"
                                        + "Write a balanced, one-paragraph recommendation.")),
                new GraphNode(briefModel, "editor", NodeType.AI_MODEL, "Editor", 1200, 220,
                        java.util.Map.of("model", "gpt-4o-mini",
                                "systemPrompt", "You write crisp decision briefs for engineering leads.",
                                "temperature", 0.3)),
                new GraphNode(brief, "brief", NodeType.OUTPUT, "Decision Brief", 1490, 220,
                        java.util.Map.of("format", "text")));

        List<GraphEdge> edges = List.of(
                new GraphEdge(UUID.randomUUID(), topic, prosPrompt, "out", "in"),
                new GraphEdge(UUID.randomUUID(), topic, consPrompt, "out", "in"),
                new GraphEdge(UUID.randomUUID(), prosPrompt, prosModel, "out", "in"),
                new GraphEdge(UUID.randomUUID(), consPrompt, consModel, "out", "in"),
                new GraphEdge(UUID.randomUUID(), prosModel, synthesis, "out", "in"),
                new GraphEdge(UUID.randomUUID(), consModel, synthesis, "out", "in"),
                new GraphEdge(UUID.randomUUID(), synthesis, briefModel, "out", "in"),
                new GraphEdge(UUID.randomUUID(), briefModel, brief, "out", "in"));

        return new Example("Balanced Research Brief",
                "Fans a question out to an advocate and a critic in parallel branches, then joins both "
                        + "answers by key into one recommendation.",
                nodes, edges);
    }

    public record Example(String name, String description, List<GraphNode> nodes, List<GraphEdge> edges) {
    }

    // --- 1. Customer support summariser ---------------------------------------------
    private Example customerSupport() {
        UUID input = UUID.randomUUID();
        UUID template = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        UUID output = UUID.randomUUID();

        List<GraphNode> nodes = List.of(
                new GraphNode(input, "customerMessage", NodeType.TEXT_INPUT, "Customer Message", 80, 120,
                        java.util.Map.of("text",
                                "Hi, my order #A-2213 arrived two days late and the packaging was damaged.")),
                new GraphNode(template, "supportPrompt", NodeType.PROMPT_TEMPLATE, "Support Prompt", 380, 120,
                        java.util.Map.of("template",
                                "You are a support agent. Write a short, empathetic reply to this customer "
                                        + "message and propose a concrete resolution.\n\n"
                                        + "Customer message: {{input}}")),
                new GraphNode(model, "draftReply", NodeType.AI_MODEL, "Draft Reply", 700, 120,
                        java.util.Map.of(
                                "model", "gpt-4o-mini",
                                "systemPrompt", "You are a calm, professional customer support agent.",
                                "temperature", 0.4)),
                new GraphNode(output, "finalReply", NodeType.OUTPUT, "Final Reply", 1000, 120,
                        java.util.Map.of("format", "text")));

        List<GraphEdge> edges = List.of(
                new GraphEdge(UUID.randomUUID(), input, template, "out", "in"),
                new GraphEdge(UUID.randomUUID(), template, model, "out", "in"),
                new GraphEdge(UUID.randomUUID(), model, output, "out", "in"));

        return new Example("Customer Support Reply",
                "Turns a raw customer message into a polished support reply using a prompt template and an AI model.",
                nodes, edges);
    }

    // --- 2. Code review -------------------------------------------------------------
    private Example codeReview() {
        UUID diff = UUID.randomUUID();
        UUID prompt = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        UUID output = UUID.randomUUID();

        List<GraphNode> nodes = List.of(
                new GraphNode(diff, "codeDiff", NodeType.TEXT_INPUT, "Code Diff", 80, 140,
                        java.util.Map.of("text",
                                "public int divide(int a, int b) {\n"
                                        + "    return a / b;\n"
                                        + "}")),
                new GraphNode(prompt, "reviewPrompt", NodeType.PROMPT_TEMPLATE, "Review Prompt", 380, 140,
                        java.util.Map.of("template",
                                "Review the following code. List bugs, then suggest improvements.\n\n"
                                        + "```\n{{input}}\n```")),
                new GraphNode(model, "reviewModel", NodeType.AI_MODEL, "Reviewer", 700, 140,
                        java.util.Map.of(
                                "model", "gpt-4o-mini",
                                "systemPrompt", "You are a meticulous senior engineer doing a code review.",
                                "temperature", 0.2)),
                new GraphNode(output, "reviewReport", NodeType.OUTPUT, "Review Report", 1000, 140,
                        java.util.Map.of("format", "text")));

        List<GraphEdge> edges = List.of(
                new GraphEdge(UUID.randomUUID(), diff, prompt, "out", "in"),
                new GraphEdge(UUID.randomUUID(), prompt, model, "out", "in"),
                new GraphEdge(UUID.randomUUID(), model, output, "out", "in"));

        return new Example("Code Review Assistant",
                "Feeds a code diff through a structured review prompt and returns a prioritised report.",
                nodes, edges);
    }

    // --- 3. Linear content pipeline --------------------------------------------------
    private Example contentPipeline() {
        UUID topic = UUID.randomUUID();
        UUID prompt = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        UUID output = UUID.randomUUID();

        List<GraphNode> nodes = new ArrayList<>(List.of(
                new GraphNode(topic, "topic", NodeType.TEXT_INPUT, "Topic", 80, 200,
                        java.util.Map.of("text", "The rise of AI coding assistants in software teams")),
                new GraphNode(prompt, "articlePrompt", NodeType.PROMPT_TEMPLATE, "Article Prompt", 380, 200,
                        java.util.Map.of("template",
                                "Write a 200-word blog post about: {{input}}")),
                new GraphNode(model, "articleModel", NodeType.AI_MODEL, "Writer", 700, 200,
                        java.util.Map.of(
                                "model", "gpt-4o-mini",
                                "systemPrompt", "You write clear, engaging technical blog posts.",
                                "temperature", 0.8)),
                new GraphNode(output, "article", NodeType.OUTPUT, "Article", 1000, 200,
                        java.util.Map.of("format", "text"))));

        List<GraphEdge> edges = List.of(
                new GraphEdge(UUID.randomUUID(), topic, prompt, "out", "in"),
                new GraphEdge(UUID.randomUUID(), prompt, model, "out", "in"),
                new GraphEdge(UUID.randomUUID(), model, output, "out", "in"));

        return new Example("Content Pipeline",
                "A minimal linear chain: topic in, article out. The simplest starting point.",
                nodes, edges);
    }
}
