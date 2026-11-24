package com.jamesstevens.epms.security.config;

import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.PwnedPasswordScreeningService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production HIBP transport; never returns SAFE on network errors. */
@Configuration
public class PasswordScreeningConfig {
    @Bean
    public PasswordScreeningDecisionService passwordScreeningDecisionService() {
        return new PasswordScreeningDecisionService(PwnedPasswordScreeningService.withHttpClient());
    }
}
