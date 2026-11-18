package com.flowcanvas;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need a real database.
 *
 * <p>The embedded PostgreSQL server is started once per JVM and the schema is built by
 * Flyway — the same migrations that run in production — so a broken migration fails the
 * test suite instead of production.
 */
@ActiveProfiles("test")
public abstract class AbstractDatabaseTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            try {
                return EmbeddedPostgresConfig.dataSourceUrl();
            } catch (Exception exception) {
                throw new IllegalStateException("Could not start embedded PostgreSQL", exception);
            }
        });
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
