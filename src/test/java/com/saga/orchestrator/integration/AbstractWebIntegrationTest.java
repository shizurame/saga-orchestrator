package com.saga.orchestrator.integration;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests that need the full web layer (MockMvc).
 *
 * Uses WebEnvironment.MOCK so controllers, filters, and exception handlers are loaded
 * without starting a real HTTP server. Shares the same PostgreSQL Testcontainer as
 * AbstractIntegrationTest (singleton instance started once per JVM).
 *
 * Spring caches this context separately from the NONE context — two contexts exist
 * per test run, each with their own Spring beans. FundTransferSaga.@PostConstruct
 * registers "fund-transfer" in each context independently; no collision occurs.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.quartz.auto-startup=false"
)
@AutoConfigureMockMvc
public abstract class AbstractWebIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      AbstractIntegrationTest.postgres::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.postgres::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.postgres::getPassword);
    }
}
