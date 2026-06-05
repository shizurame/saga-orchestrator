package com.saga.orchestrator.scanning;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz job wrapper for TimeoutScanService.
 *
 * Runs every 5 seconds (configured in QuartzJobsConfig).
 * Spring auto-wires TimeoutScanService via AutowireCapableBeanJobFactory.
 * @DisallowConcurrentExecution prevents overlapping executions on the same node.
 */
@DisallowConcurrentExecution
public class TimeoutScanner implements Job {

    @Autowired
    private TimeoutScanService scanService;

    @Override
    public void execute(JobExecutionContext context) {
        scanService.scan();
    }
}
