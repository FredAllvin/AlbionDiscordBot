package personal.albiondiscordbot.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared Postgres container for integration tests.
 *
 * <p>Deliberately a real Postgres rather than H2: the schema relies on partial unique
 * indexes, {@code ON CONFLICT … RETURNING (xmax = 0)} and {@code timestamptz}, and H2's
 * Postgres compatibility mode misreports all of them. A test that passes against H2 but
 * fails in production is worse than no test.
 *
 * <p>This uses the singleton-container pattern — started once in a static initialiser
 * and never explicitly stopped — rather than JUnit's {@code @Testcontainers}/
 * {@code @Container} lifecycle. Those annotations stop the container after each test
 * <em>class</em>, which leaves every class after the first pointing at a dead port.
 * Testcontainers' Ryuk sidecar removes the container when the JVM exits.
 */
public abstract class PostgresTestBase {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // BotProperties is @Validated with @NotBlank, so tests must supply something.
        registry.add("bot.token", () -> "test-token-not-used");
        // The scheduled battle poller would otherwise call the live Albion API mid-test.
        registry.add("albion.poller.enabled", () -> "false");
    }
}
