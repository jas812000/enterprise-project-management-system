package com.jamesstevens.epms.security.service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded periodic recovery. Database claims fence competing application instances. */
@Component
public class DeferredRecoveryScheduler {
    private static final int MAX_RECOVERY_BATCH_SIZE = 100;
    private static final Logger LOG = LoggerFactory.getLogger(DeferredRecoveryScheduler.class);
    private final DeferredRecoveryBatchCoordinator coordinator;
    private final int batchSize;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public DeferredRecoveryScheduler(DeferredRecoveryBatchCoordinator coordinator,
            @Value("${epms.security.deferred-recovery.batch-size:25}") int batchSize,
            @Value("${epms.security.deferred-recovery.enabled:true}") boolean enabled) {
        this.coordinator = Objects.requireNonNull(coordinator);
        if (batchSize < 1 || batchSize > MAX_RECOVERY_BATCH_SIZE) {
            throw new IllegalArgumentException("Deferred recovery batch size must be between 1 and 100");
        }
        this.batchSize = batchSize;
        this.enabled = enabled;
    }

    /** Fixed operational categories, deliberately independent of exception text. */
    @SuppressWarnings({"PMD.CommentDefaultAccessModifier", "PMD.OnlyOneReturn"})
    // Fixed categories prevent leaking exception details; each branch returns immediately.
    static String failureCategory(RuntimeException error) {
        if (error instanceof org.springframework.dao.DataAccessException) {
            return "DATABASE";
        }
        if (error instanceof org.springframework.transaction.TransactionException) {
            return "TRANSACTION";
        }
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            return "APPLICATION_STATE";
        }
        return "UNCLASSIFIED";
    }

    @Scheduled(initialDelayString = "${epms.security.deferred-recovery.initial-delay-ms:0}",
               fixedDelayString = "${epms.security.deferred-recovery.interval-ms:60000}")
    public void reconcile() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            DeferredRecoveryBatchCoordinator.Result result = coordinator.process(batchSize);
            if (LOG.isInfoEnabled() && (result.attempted() > 0 || result.failed() > 0)) {
                // Only aggregate counters; never credentials, exception messages or screening data.
                LOG.info("Deferred recovery: attempted={}, verified={}, changeRequired={},"
                                + " unavailable={}, notPending={}, failed={}",
                        result.attempted(), result.verified(), result.changeRequired(),
                        result.unavailable(), result.notPending(), result.failed());
            }
        } catch (RuntimeException error) { // NOPMD - Scheduled recovery must retry after any runtime failure.
            // Do not pass the throwable: its message/stack may expose screening material.
            // Report only a fixed category. Never log exception messages, stack traces,
            // database identifiers, credentials, or screening payloads.
            if (LOG.isWarnEnabled()) {
                LOG.warn("Deferred recovery reconciliation failed (category={}); next scheduled run will retry",
                        failureCategory(error));
            }
        } finally {
            running.set(false);
        }
    }
}
