package com.flowcanvas;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * An embedded PostgreSQL server for integration tests.
 *
 * <p>Why not Testcontainers? Testcontainers needs a Docker daemon, which makes the test
 * suite unrunnable on a plain laptop or in a CI job without Docker-in-Docker. The embedded
 * server gives us a <b>real</b> PostgreSQL — real {@code jsonb}, real constraints, real
 * transactional semantics — with zero external dependencies, so {@code mvn test} always
 * means the same thing.
 *
 * <p>It is also much faster: the server starts in about a second.
 *
 * <p>The server is started lazily once per JVM and shared by every test class; Spring's
 * Flyway migration then creates the schema in it exactly as it would in production.
 */
public final class EmbeddedPostgresConfig {

    private static EmbeddedPostgres sharedPostgres;
    private static String jdbcUrl;

    private EmbeddedPostgresConfig() {
    }

    /** Starts the shared server once per JVM and returns its JDBC URL. */
    public static synchronized String dataSourceUrl() throws IOException {
        if (sharedPostgres == null) {
            sharedPostgres = EmbeddedPostgres.start();
            jdbcUrl = sharedPostgres.getJdbcUrl("postgres", "postgres");
        }
        return jdbcUrl;
    }

    /** Runs a statement against the embedded server — used by tests that need raw SQL. */
    public static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
