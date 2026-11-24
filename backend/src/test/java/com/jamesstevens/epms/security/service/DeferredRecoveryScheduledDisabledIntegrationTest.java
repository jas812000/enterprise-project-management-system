package com.jamesstevens.epms.security.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** A disabled scheduler may be invoked by Spring but must never claim credentials. */
@SpringJUnitConfig(DeferredRecoveryScheduledDisabledIntegrationTest.DisabledConfig.class)
@TestPropertySource(properties = {
        "epms.security.deferred-recovery.enabled=false",
        "epms.security.deferred-recovery.batch-size=7",
        "epms.security.deferred-recovery.initial-delay-ms=0",
        "epms.security.deferred-recovery.interval-ms=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeferredRecoveryScheduledDisabledIntegrationTest {
    @Autowired DeferredRecoveryBatchCoordinator coordinator;

    @Test
    void disabledSchedulerNeverProcessesClaims() throws InterruptedException {
        Thread.sleep(450);
        verifyNoInteractions(coordinator);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(DeferredRecoveryScheduler.class)
    static class DisabledConfig {
        @Bean DeferredRecoveryBatchCoordinator coordinator() {
            return mock(DeferredRecoveryBatchCoordinator.class);
        }
    }
}
