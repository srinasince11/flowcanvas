package com.flowcanvas.graph;

import java.util.List;

/**
 * A single problem found while validating a graph.
 *
 * @param severity  {@code ERROR} blocks execution, {@code WARNING} does not.
 * @param code      stable machine-readable identifier, e.g. {@code CYCLE_DETECTED}.
 * @param message   human-readable explanation, safe to show in the UI.
 * @param nodeId    offending node, when the problem is node-scoped.
 * @param edgeId    offending edge, when the problem is edge-scoped.
 */
public record ValidationIssue(
        Severity severity,
        String code,
        String message,
        java.util.UUID nodeId,
        java.util.UUID edgeId
) {
    public enum Severity {
        ERROR,
        WARNING
    }

    public static ValidationIssue error(String code, String message) {
        return new ValidationIssue(Severity.ERROR, code, message, null, null);
    }

    public static ValidationIssue error(String code, String message, java.util.UUID nodeId) {
        return new ValidationIssue(Severity.ERROR, code, message, nodeId, null);
    }

    public static ValidationIssue error(String code, String message, java.util.UUID nodeId, java.util.UUID edgeId) {
        return new ValidationIssue(Severity.ERROR, code, message, nodeId, edgeId);
    }

    public static ValidationIssue warning(String code, String message) {
        return new ValidationIssue(Severity.WARNING, code, message, null, null);
    }

    public static ValidationIssue warning(String code, String message, java.util.UUID nodeId) {
        return new ValidationIssue(Severity.WARNING, code, message, nodeId, null);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public static boolean hasErrors(List<ValidationIssue> issues) {
        return issues.stream().anyMatch(ValidationIssue::isError);
    }
}
