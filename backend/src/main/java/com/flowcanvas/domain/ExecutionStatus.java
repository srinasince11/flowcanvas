package com.flowcanvas.domain;

/** Lifecycle status of a workflow run. */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}
