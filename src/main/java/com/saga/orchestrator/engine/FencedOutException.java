package com.saga.orchestrator.engine;

import java.util.UUID;

/**
 * Thrown when a write is attempted for a saga this node tracks with a fencing token that no
 * longer matches the one currently stored on the row — i.e. another node has since claimed
 * the saga (its lease expired, or it crashed and was reclaimed) and this node's in-memory
 * belief about owning it is stale.
 *
 * Only thrown for sagas this node currently tracks (see HeartbeatService.trackedFencingToken).
 * A saga this node never tracked is not fenced — that is a different, pre-existing path
 * (resuming a saga directly, e.g. in tests or crash-recovery flows that call engine methods
 * without going through claimSaga/track first) and is intentionally left unaffected.
 */
public class FencedOutException extends RuntimeException {

    private final UUID sagaId;
    private final long trackedToken;
    private final long currentToken;

    public FencedOutException(UUID sagaId, long trackedToken, long currentToken) {
        super("Fenced out of saga " + sagaId + ": this node tracked fencing_token=" + trackedToken
                + " but the row is now at fencing_token=" + currentToken
                + " — another node has claimed it since");
        this.sagaId = sagaId;
        this.trackedToken = trackedToken;
        this.currentToken = currentToken;
    }

    public UUID getSagaId()      { return sagaId; }
    public long getTrackedToken() { return trackedToken; }
    public long getCurrentToken() { return currentToken; }
}
