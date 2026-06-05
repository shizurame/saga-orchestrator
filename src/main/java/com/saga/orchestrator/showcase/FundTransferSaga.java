package com.saga.orchestrator.showcase;

import com.saga.orchestrator.definition.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 5-step financial saga exercising all seven hard problems:
 *
 *   Step 0 — credit-check    : COMPENSATABLE, 5s, RETRY_WITH_IDEMPOTENCY
 *   Step 1 — reserve-funds   : COMPENSATABLE, 5s, RETRY_WITH_IDEMPOTENCY
 *   Step 2 — debit-account   : PIVOT,         10s, WAIT_FOR_CALLBACK
 *   Step 3 — credit-beneficiary: RETRYABLE,   5s, RETRY_WITH_IDEMPOTENCY
 *   Step 4 — send-notification : RETRYABLE,   3s, RETRY_WITH_IDEMPOTENCY
 *
 * The pivot step (debit-account) is the point of no return. Steps before it are
 * COMPENSATABLE (compensation rolls back the hold), steps after are RETRYABLE
 * (the debit already succeeded, so retrying the credit is the only safe option).
 *
 * Entity locking: sourceAccountId is locked for the saga's duration (EntityRef "account").
 * This prevents concurrent sagas from over-drawing the same source account.
 */
@Component
public class FundTransferSaga {

    public static final String SAGA_TYPE       = "fund-transfer";
    public static final String ENTITY_TYPE_ACCOUNT = "account";

    private final SagaDefinitionRegistry  registry;
    private final CreditCheckService      creditCheckService;
    private final FundReservationService  fundReservationService;
    private final DebitService            debitService;
    private final CreditService           creditService;
    private final NotificationService     notificationService;

    public FundTransferSaga(SagaDefinitionRegistry registry,
                             CreditCheckService creditCheckService,
                             FundReservationService fundReservationService,
                             DebitService debitService,
                             CreditService creditService,
                             NotificationService notificationService) {
        this.registry             = registry;
        this.creditCheckService   = creditCheckService;
        this.fundReservationService = fundReservationService;
        this.debitService         = debitService;
        this.creditService        = creditService;
        this.notificationService  = notificationService;
    }

    @PostConstruct
    void register() {
        SagaDefinition<TransferContext> definition = SagaDefinition
                .named(SAGA_TYPE, TransferContext.class)

                .compensatableStep("credit-check")
                    .action(creditCheckService.forwardAction())
                    .compensation(creditCheckService.compensationAction())
                    .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                    .retryPolicy(RetryPolicy.exponential(3, Duration.ofMillis(100)))

                .compensatableStep("reserve-funds")
                    .action(fundReservationService.forwardAction())
                    .compensation(fundReservationService.compensationAction())
                    .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                    .retryPolicy(RetryPolicy.exponential(3, Duration.ofMillis(100)))

                .pivotStep("debit-account")
                    .action(debitService)
                    .timeout(Duration.ofSeconds(10), TimeoutMode.WAIT_FOR_CALLBACK)
                    .retryPolicy(RetryPolicy.noRetry())

                .retryableStep("credit-beneficiary")
                    .action(creditService.forwardAction())
                    .timeout(Duration.ofSeconds(5), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                    .retryPolicy(RetryPolicy.exponential(3, Duration.ofMillis(100)))

                .retryableStep("send-notification")
                    .action(notificationService.forwardAction())
                    .timeout(Duration.ofSeconds(3), TimeoutMode.RETRY_WITH_IDEMPOTENCY)
                    .retryPolicy(RetryPolicy.exponential(3, Duration.ofMillis(100)))

                .build();

        registry.register(definition);
    }
}
