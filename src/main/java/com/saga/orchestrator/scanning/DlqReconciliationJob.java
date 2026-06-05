package com.saga.orchestrator.scanning;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Quartz job that fires every 5 minutes to surface DEAD_LETTER sagas.
 * Thin wrapper — all logic lives in DlqReconciliationService for direct testability.
 */
@Component
@DisallowConcurrentExecution
public class DlqReconciliationJob implements Job {

    @Autowired
    private DlqReconciliationService service;

    @Override
    public void execute(JobExecutionContext context) {
        service.reconcile();
    }
}
