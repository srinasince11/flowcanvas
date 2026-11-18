package com.flowcanvas.config;

import com.flowcanvas.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Closes runs that a previous server process left in flight.
 *
 * <p>Runs execute on an in-memory pool, so a crash or redeploy abandons them mid-way. At
 * startup — before this process has started any run of its own — anything still marked
 * {@code RUNNING} is necessarily orphaned, and is failed with an explicit reason instead of
 * spinning in the UI forever.
 */
@Component
@Order(0)
public class InterruptedRunRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterruptedRunRecovery.class);

    private final WorkflowService workflowService;

    public InterruptedRunRecovery(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int closed = workflowService.failInterruptedRuns();
        if (closed > 0) {
            log.warn("Marked {} run(s) interrupted by the previous shutdown as FAILED", closed);
        }
    }
}
