package com.saga.orchestrator.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationSmokeTest extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void contextLoads() {
        // Spring context started successfully with Flyway + JPA + Quartz wired up
    }

    @Test
    void flywayCreatedAllFourTables() throws SQLException {
        Set<String> tables = new HashSet<>();
        try (var conn = dataSource.getConnection();
             var rs = conn.getMetaData().getTables(null, "public", null, new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        assertThat(tables).contains(
            "saga_instance",
            "saga_step_executions",
            "compensation_steps",
            "saga_entity_locks"
        );
    }

    @Test
    void flywayAppliedAllMigrations() throws SQLException {
        int count;
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")) {
            rs.next();
            count = rs.getInt(1);
        }
        // V1 through V5 — adjust this number as migrations are added in later rounds
        assertThat(count).isEqualTo(5);
    }
}
