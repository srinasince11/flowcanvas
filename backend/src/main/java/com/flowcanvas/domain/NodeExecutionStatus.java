package com.flowcanvas.domain;

/** Lifecycle status of a single node within a workflow run. */
public enum NodeExecutionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
