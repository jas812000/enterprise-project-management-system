package com.jamesstevens.epms.security.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables deferred screening reconciliation on running application instances. */
@Configuration
@EnableScheduling
public class DeferredRecoverySchedulingConfig { }
