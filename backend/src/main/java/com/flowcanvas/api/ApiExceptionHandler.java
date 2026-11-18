package com.flowcanvas.api;

import com.flowcanvas.api.dto.WorkflowDtos;
import com.flowcanvas.graph.ValidationIssue;
import com.flowcanvas.service.WorkflowService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into RFC 7807 problem responses.
 *
 * <p>Every error the API can return has a stable {@code code} so the frontend can react
 * programmatically (e.g. show "fix your connections" for {@code CYCLE_DETECTED}) instead of
 * pattern-matching on human-readable messages.
 */
@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WorkflowService.WorkflowNotFoundException.class)
    public ProblemDetail handleWorkflowNotFound(WorkflowService.WorkflowNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setTitle("Workflow not found");
        detail.setProperty("code", "WORKFLOW_NOT_FOUND");
        return detail;
    }

    @ExceptionHandler(WorkflowService.ExecutionNotFoundException.class)
    public ProblemDetail handleExecutionNotFound(WorkflowService.ExecutionNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setTitle("Execution not found");
        detail.setProperty("code", "EXECUTION_NOT_FOUND");
        return detail;
    }

    @ExceptionHandler(WorkflowService.GraphNotStorableException.class)
    public ProblemDetail handleGraphNotStorable(WorkflowService.GraphNotStorableException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                exception.getMessage());
        detail.setTitle("Graph cannot be saved");
        detail.setProperty("code", "GRAPH_NOT_STORABLE");
        detail.setProperty("issues", exception.issues().stream()
                .map(issue -> new WorkflowDtos.IssuePayload(issue.severity().name(), issue.code(),
                        issue.message(), issue.nodeId(), issue.edgeId()))
                .toList());
        return detail;
    }

    /**
     * A write lost a race with a concurrent change (e.g. two saves of the same workflow, or a
     * delete while saving). That is a conflict the client can retry, not a server fault.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ProblemDetail handleConflict(org.springframework.dao.DataIntegrityViolationException exception) {
        log.warn("Rejected a write that violated a database constraint: {}",
                exception.getMostSpecificCause().getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The change conflicts with the current state of the workflow. Reload and try again.");
        detail.setTitle("Conflict");
        detail.setProperty("code", "CONFLICT");
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage()))
                .toList();
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body failed validation.");
        detail.setTitle("Invalid request");
        detail.setProperty("code", "VALIDATION_FAILED");
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Invalid request");
        detail.setProperty("code", "INVALID_ARGUMENT");
        return detail;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException exception) {
        // A malformed body (bad UUID, wrong JSON shape) is the client's mistake and must
        // be a 400 — not a 500. The root cause is logged, but the client gets a clean,
        // actionable message.
        log.warn("Rejected an unreadable request body: {}", exception.getMostSpecificCause().getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body could not be parsed. Check that identifiers are valid UUIDs "
                        + "and that the payload matches the documented schema.");
        detail.setTitle("Malformed request body");
        detail.setProperty("code", "MALFORMED_REQUEST");
        return detail;
    }

    /**
     * A path variable could not be converted (e.g. a malformed UUID).
     *
     * <p>This is a client error, not a server fault: without this handler the request
     * would surface as a 500 and make a typo look like an outage.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The value '" + exception.getValue() + "' is not valid for parameter '"
                        + exception.getName() + "'.");
        detail.setTitle("Invalid parameter");
        detail.setProperty("code", "INVALID_PARAMETER");
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        // Log the full stack trace server-side: the client gets a generic message (no
        // internals leaked), but the cause must never be lost.
        log.error("Unhandled exception while serving a request", exception);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Check the server logs for details.");
        detail.setTitle("Internal server error");
        detail.setProperty("code", "INTERNAL_ERROR");
        return detail;
    }
}
