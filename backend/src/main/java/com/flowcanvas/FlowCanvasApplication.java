package com.flowcanvas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * FlowCanvas backend.
 *
 * <p>A workflow-builder API: persist workflow graphs, validate them, and execute them in
 * dependency order against a pluggable AI provider.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class FlowCanvasApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowCanvasApplication.class, args);
    }
}
