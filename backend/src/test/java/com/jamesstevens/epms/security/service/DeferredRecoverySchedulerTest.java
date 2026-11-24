package com.jamesstevens.epms.security.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class DeferredRecoverySchedulerTest {
    private final DeferredRecoveryBatchCoordinator coordinator = mock(DeferredRecoveryBatchCoordinator.class);

    @Test void executesBoundedBatchWhenEnabled() {
        new DeferredRecoveryScheduler(coordinator, 25, true).reconcile();
        verify(coordinator).process(25);
    }

    @Test void disabledSchedulerDoesNotClaim() {
        new DeferredRecoveryScheduler(coordinator, 25, false).reconcile();
        verifyNoInteractions(coordinator);
    }

    @Test void rejectsInvalidBatchSizes() {
        assertThrows(IllegalArgumentException.class, () -> new DeferredRecoveryScheduler(coordinator, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new DeferredRecoveryScheduler(coordinator, 101, true));
    }

    @Test void coordinatorFailureDoesNotEscapeScheduledInvocation() {
        doThrow(new IllegalStateException("synthetic failure")).when(coordinator).process(25);
        assertDoesNotThrow(() -> new DeferredRecoveryScheduler(coordinator, 25, true).reconcile());
        verify(coordinator).process(25);
    }

    @Test void overlappingInvocationsDoNotStartSecondBatch() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(coordinator.process(25)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out");
            }
            return new DeferredRecoveryBatchCoordinator.Result(0, 0, 0, 0, 0, 0);
        });
        var scheduler = new DeferredRecoveryScheduler(coordinator, 25, true);
        var thread = new Thread(scheduler::reconcile);
        thread.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            scheduler.reconcile();
            verify(coordinator, times(1)).process(25);
        } finally {
            release.countDown();
            thread.join(5000);
        }
        assertFalse(thread.isAlive());
    }

    @Test void failedRunReleasesOverlapGuard() {
        when(coordinator.process(25))
                .thenThrow(new IllegalStateException("do not log secret"))
                .thenReturn(new DeferredRecoveryBatchCoordinator.Result(0, 0, 0, 0, 0, 0));
        var scheduler = new DeferredRecoveryScheduler(coordinator, 25, true);
        assertDoesNotThrow(scheduler::reconcile);
        assertDoesNotThrow(scheduler::reconcile);
        verify(coordinator, times(2)).process(25);
    }


    @Test void failureCategoriesDoNotExposeExceptionMessages() {
        assertEquals("DATABASE", DeferredRecoveryScheduler.failureCategory(
                new org.springframework.dao.DataAccessResourceFailureException("secret credential")));
        assertEquals("TRANSACTION", DeferredRecoveryScheduler.failureCategory(
                new org.springframework.transaction.CannotCreateTransactionException("secret token")));
        assertEquals("APPLICATION_STATE", DeferredRecoveryScheduler.failureCategory(
                new IllegalStateException("secret screening payload")));
        assertEquals("UNCLASSIFIED", DeferredRecoveryScheduler.failureCategory(
                new RuntimeException("secret database identifier")));
    }

    @Test void failureWarningContainsOnlySafeCategory() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(DeferredRecoveryScheduler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(coordinator.process(25)).thenThrow(
                    new org.springframework.dao.DataAccessResourceFailureException("SECRET_SCREENING_TOKEN"));
            new DeferredRecoveryScheduler(coordinator, 25, true).reconcile();
            assertEquals(1, appender.list.size());
            var event = appender.list.get(0);
            assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("category=DATABASE"));
            assertFalse(event.getFormattedMessage().contains("SECRET_SCREENING_TOKEN"));
            assertNull(event.getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test void scheduledMethodRetainsStartupAndPeriodicDefaults() throws Exception {
        var method = DeferredRecoveryScheduler.class.getMethod("reconcile");
        var scheduled = method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertNotNull(scheduled, "Spring must discover the scheduled method");
        assertEquals("${epms.security.deferred-recovery.initial-delay-ms:0}",
                scheduled.initialDelayString());
        assertEquals("${epms.security.deferred-recovery.interval-ms:60000}",
                scheduled.fixedDelayString());
        assertTrue(DeferredRecoveryScheduler.class.isAnnotationPresent(
                org.springframework.stereotype.Component.class));
        assertTrue(com.jamesstevens.epms.security.config.DeferredRecoverySchedulingConfig.class
                .isAnnotationPresent(org.springframework.scheduling.annotation.EnableScheduling.class));
    }

}
