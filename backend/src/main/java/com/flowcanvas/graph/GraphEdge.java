package com.flowcanvas.graph;

import java.util.UUID;

/** A directed edge in the in-memory graph. */
public record GraphEdge(
        UUID id,
        UUID sourceId,
        UUID targetId,
        String sourceHandle,
        String targetHandle
) {
}
