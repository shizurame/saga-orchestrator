package com.saga.orchestrator.scanning;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz job wrapper for OrphanedSagaScanService.
 *
 * Fires on startup (startNow) and every 30 seconds (configured in QuartzJobsConfig).
 * Spring auto-wires OrphanedSagaScanService via AutowireCapableBeanJobFactory.
 * @DisallowConcurrentExecution prevents overlapping recovery attempts on the same node.
 */
@DisallowConcurrentExecution
public class OrphanedSagaScanner implements Job {

    @Autowired
    private OrphanedSagaScanService scanService;

    @Override
    public void execute(JobExecutionContext context) {
        scanService.scan();
    }
}
