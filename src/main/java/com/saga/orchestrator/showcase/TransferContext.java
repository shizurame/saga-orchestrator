package com.saga.orchestrator.showcase;

import com.saga.orchestrator.definition.SagaContext;
import java.math.BigDecimal;

public class TransferContext implements SagaContext {

    private String     customerId;
    private String     sourceAccountId;
    private String     destAccountId;
    private BigDecimal amount;
    private String     reservationId;    // set by FundReservationService on forward
    private String     authorizationId;  // set by DebitService on forward
    private String     transferId;       // set by CreditService on forward

    public TransferContext() {}

    public TransferContext(String customerId, String sourceAccountId,
                           String destAccountId, BigDecimal amount) {
        this.customerId      = customerId;
        this.sourceAccountId = sourceAccountId;
        this.destAccountId   = destAccountId;
        this.amount          = amount;
    }

    public String     getCustomerId()       { return customerId; }
    public String     getSourceAccountId()  { return sourceAccountId; }
    public String     getDestAccountId()    { return destAccountId; }
    public BigDecimal getAmount()           { return amount; }
    public String     getReservationId()    { return reservationId; }
    public String     getAuthorizationId()  { return authorizationId; }
    public String     getTransferId()       { return transferId; }

    public void setCustomerId(String v)      { this.customerId = v; }
    public void setSourceAccountId(String v) { this.sourceAccountId = v; }
    public void setDestAccountId(String v)   { this.destAccountId = v; }
    public void setAmount(BigDecimal v)      { this.amount = v; }
    public void setReservationId(String v)   { this.reservationId = v; }
    public void setAuthorizationId(String v) { this.authorizationId = v; }
    public void setTransferId(String v)      { this.transferId = v; }
}
