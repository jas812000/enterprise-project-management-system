package com.jamesstevens.epms.security.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Verifies Spring, not a direct Java call, triggers startup and repeated recovery. */
@SpringJUnitConfig(DeferredRecoveryScheduledRuntimeIntegrationTest.SchedulingTestConfig.class)
@TestPropertySource(properties = {
        "epms.security.deferred-recovery.enabled=true",
        "epms.security.deferred-recovery.batch-size=7",
        "epms.security.deferred-recovery.initial-delay-ms=0",
        "epms.security.deferred-recovery.interval-ms=150"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeferredRecoveryScheduledRuntimeIntegrationTest {
    @Autowired DeferredRecoveryBatchCoordinator coordinator;

    @Test
    void springStartsSchedulerAndInvokesItRepeatedly() {
        verify(coordinator, timeout(8000).atLeast(2)).process(eq(7));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(DeferredRecoveryScheduler.class)
    static class SchedulingTestConfig {
        @Bean DeferredRecoveryBatchCoordinator coordinator() {
            var coordinator = mock(DeferredRecoveryBatchCoordinator.class);
            when(coordinator.process(7)).thenReturn(
                    new DeferredRecoveryBatchCoordinator.Result(0, 0, 0, 0, 0, 0));
            return coordinator;
        }
    }
}
