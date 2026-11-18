package com.flowcanvas.engine;

/** Thrown when a node cannot be executed. */
public class NodeExecutionException extends RuntimeException {

    public NodeExecutionException(String message) {
        super(message);
    }

    public NodeExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
