package com.saga.orchestrator.engine;

import com.saga.orchestrator.definition.SagaStep;
import org.springframework.stereotype.Component;

@Component
public class StepExecutorFactory {

    private final BeanStepExecutor beanExecutor;
    private final HttpStepExecutor httpExecutor;

    public StepExecutorFactory(BeanStepExecutor beanExecutor, HttpStepExecutor httpExecutor) {
        this.beanExecutor = beanExecutor;
        this.httpExecutor = httpExecutor;
    }

    public StepActionExecutor select(SagaStep step) {
        return httpExecutor.supports(step.action()) ? httpExecutor : beanExecutor;
    }
}
