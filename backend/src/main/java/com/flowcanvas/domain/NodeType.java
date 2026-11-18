package com.flowcanvas.domain;

/**
 * The node taxonomy supported by FlowCanvas.
 *
 * <p>Every node type declares how many incoming and outgoing edges it tolerates. Those
 * bounds are the single source of truth for connection validation — the validator, the
 * frontend palette and the API docs all read them from here so they can never drift apart.
 *
 * <p>Contract summary:
 * <ul>
 *   <li>{@code TEXT_INPUT} — a literal string. Pure source, no incoming edges.</li>
 *   <li>{@code PROMPT_TEMPLATE} — renders a template against upstream values. Joins one or
 *       more branches and may read any ancestor through {@code {{nodeKey}}} placeholders.</li>
 *   <li>{@code AI_MODEL} — asks the configured {@code AiProvider} to complete one prompt.</li>
 *   <li>{@code OUTPUT} — terminal sink. Joins one or more incoming branches.</li>
 * </ul>
 */
public enum NodeType {

    TEXT_INPUT("Text Input", 0, 0, 0, Integer.MAX_VALUE),
    PROMPT_TEMPLATE("Prompt Template", 1, Integer.MAX_VALUE, 0, Integer.MAX_VALUE),
    AI_MODEL("AI Model", 1, 1, 0, Integer.MAX_VALUE),
    OUTPUT("Output", 1, Integer.MAX_VALUE, 0, 0);

    private final String displayName;
    private final int minIncoming;
    private final int maxIncoming;
    private final int minOutgoing;
    private final int maxOutgoing;

    NodeType(String displayName, int minIncoming, int maxIncoming, int minOutgoing, int maxOutgoing) {
        this.displayName = displayName;
        this.minIncoming = minIncoming;
        this.maxIncoming = maxIncoming;
        this.minOutgoing = minOutgoing;
        this.maxOutgoing = maxOutgoing;
    }

    public String displayName() {
        return displayName;
    }

    public int minIncoming() {
        return minIncoming;
    }

    public int maxIncoming() {
        return maxIncoming;
    }

    public int minOutgoing() {
        return minOutgoing;
    }

    public int maxOutgoing() {
        return maxOutgoing;
    }

    /** Whether a node of this type may terminate a branch. */
    public boolean isSink() {
        return maxOutgoing == 0;
    }

    /** Whether a node of this type may start a branch. */
    public boolean isSource() {
        return minIncoming == 0;
    }

    public static NodeType fromWire(String value) {
        if (value == null) {
            return null;
        }
        String normalised = value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (NodeType type : values()) {
            if (type.name().equals(normalised)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown node type: " + value);
    }
}
