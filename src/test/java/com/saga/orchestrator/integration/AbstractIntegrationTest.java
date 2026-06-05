package com.saga.orchestrator.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for all integration tests.
 *
 * Uses a singleton container pattern: the PostgreSQL container is started ONCE
 * when this class is first loaded and stays alive for the full JVM run (Ryuk cleans
 * it up on exit). This avoids the lifecycle bug where @Testcontainers stops a static
 * container after the first test class finishes, leaving subsequent test classes with a
 * closed connection pool.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.quartz.auto-startup=false"
)
public abstract class AbstractIntegrationTest {

    // package-private so AbstractWebIntegrationTest can reference the same container
    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
