package com.saga.orchestrator.scanning;

import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the two background Quartz jobs: TimeoutScanner and OrphanedSagaScanner.
 *
 * TimeoutScanner:        every 5 seconds — retries IN_PROGRESS steps past their timeout_at
 * OrphanedSagaScanner:   startNow + every 30 seconds — reclaims sagas from crashed nodes
 *
 * Both jobs use @DisallowConcurrentExecution. In a clustered deployment (Quartz JDBC store
 * with isClustered=true), Quartz ensures only one node runs the job at any given time.
 *
 * spring.quartz.auto-startup=false in test properties prevents these from firing during tests.
 * Tests inject TimeoutScanService / OrphanedSagaScanService directly and call scan() manually.
 */
@Configuration
public class QuartzJobsConfig {

    @Bean
    public JobDetail timeoutScannerJobDetail() {
        return JobBuilder.newJob(TimeoutScanner.class)
                .withIdentity("timeout-scanner")
                .storeDurably()
                .build();
    }

    @Bean
    public SimpleTrigger timeoutScannerTrigger(JobDetail timeoutScannerJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(timeoutScannerJobDetail)
                .withIdentity("timeout-scanner-trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(5)
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail orphanedSagaScannerJobDetail() {
        return JobBuilder.newJob(OrphanedSagaScanner.class)
                .withIdentity("orphaned-saga-scanner")
                .storeDurably()
                .build();
    }

    @Bean
    public SimpleTrigger orphanedSagaScannerTrigger(JobDetail orphanedSagaScannerJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(orphanedSagaScannerJobDetail)
                .withIdentity("orphaned-saga-scanner-trigger")
                .startNow()
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(30)
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail dlqReconciliationJobDetail() {
        return JobBuilder.newJob(DlqReconciliationJob.class)
                .withIdentity("dlq-reconciliation")
                .storeDurably()
                .build();
    }

    @Bean
    public SimpleTrigger dlqReconciliationTrigger(JobDetail dlqReconciliationJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(dlqReconciliationJobDetail)
                .withIdentity("dlq-reconciliation-trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(300)
                        .repeatForever())
                .build();
    }
}
