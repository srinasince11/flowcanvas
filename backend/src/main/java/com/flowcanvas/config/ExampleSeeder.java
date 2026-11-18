package com.flowcanvas.config;

import com.flowcanvas.service.ExampleWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds the example workflows on first start.
 *
 * <p>Controlled by {@code SEED_EXAMPLES} (default {@code true}). The seed is idempotent — it
 * only writes when the workflow table is empty.
 */
@Component
@ConditionalOnProperty(name = "flowcanvas.seed.examples", havingValue = "true", matchIfMissing = true)
public class ExampleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExampleSeeder.class);

    private final ExampleWorkflowService exampleWorkflowService;

    public ExampleSeeder(ExampleWorkflowService exampleWorkflowService) {
        this.exampleWorkflowService = exampleWorkflowService;
    }

    @Override
    public void run(ApplicationArguments args) {
        exampleWorkflowService.seedIfEmpty();
        log.info("Example workflows are available at GET /api/v1/workflows/examples");
    }
}
