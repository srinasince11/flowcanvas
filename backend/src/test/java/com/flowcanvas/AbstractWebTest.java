package com.flowcanvas;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Base class for {@code @SpringBootTest} web-layer tests.
 *
 * <p>Extends {@link AbstractDatabaseTest} so the embedded PostgreSQL is configured and
 * Flyway runs the production migrations.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractWebTest extends AbstractDatabaseTest {
}
