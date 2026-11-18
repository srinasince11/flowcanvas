package com.flowcanvas.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Application-level beans: CORS, the REST client and the workflow execution pool. */
@Configuration
public class AppConfig {

    /**
     * CORS for the Vite dev server.
     *
     * <p>Only the dev origins are allowed — there is no wildcard, because the API is
     * unauthenticated in this project and should not be reachable from arbitrary origins
     * in a deployed environment.
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(
                                "http://localhost:5173",
                                "http://127.0.0.1:5173",
                                "http://localhost:4173",
                                "http://127.0.0.1:4173")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Bounded pool for workflow runs.
     *
     * <p>A fixed pool with a bounded queue and {@code CallerRunsPolicy}: if the queue is
     * saturated the submitting thread runs the job instead of silently dropping it. Back
     * pressure is visible rather than lost.
     */
    @Bean(name = "workflowExecutor")
    public Executor workflowExecutor(ExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.maxConcurrent());
        executor.setMaxPoolSize(properties.maxConcurrent());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("flow-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }

    /** Execution-pool tuning, bound from {@code flowcanvas.execution.*}. */
    @ConfigurationProperties(prefix = "flowcanvas.execution")
    public record ExecutionProperties(int maxConcurrent, int queueCapacity) {
        public ExecutionProperties {
            maxConcurrent = maxConcurrent <= 0 ? 4 : maxConcurrent;
            queueCapacity = queueCapacity <= 0 ? 64 : queueCapacity;
        }
    }
}
