package com.jamesstevens.epms.security.bootstrap;

import com.jamesstevens.epms.security.service.PasswordScreeningDecisionService;
import com.jamesstevens.epms.security.service.PwnedPasswordScreeningService;

/** Test-classpath-only launcher. Not packaged in the application artifact. */
public final class BootstrapCliIsolatedTestLauncher {
    private BootstrapCliIsolatedTestLauncher() { }

    public static void main(String[] args) {
        String screeningMode = System.getenv("EPMS_TEST_BOOTSTRAP_SCREENING_MODE");
        BootstrapPasswordPreflight preflight = new BootstrapPasswordPreflight(
                new PasswordScreeningDecisionService(new PwnedPasswordScreeningService(prefix -> {
                    if (!prefix.matches("[0-9A-F]{5}")) {
                        throw new IllegalArgumentException("Invalid screening prefix");
                    }
                    if ("OUTAGE".equals(screeningMode)) {
                        throw new IllegalStateException("Simulated screening outage");
                    }
                    if ("COMPROMISED".equals(screeningMode)) {
                        return "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:1";
                    }
                    return "00000000000000000000000000000000000:1";
                })));
        InitialAdminBootstrapCli.run(args, System.console(), preflight);
    }
}
