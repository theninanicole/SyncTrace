package com.ieee.evaluator.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;

/**
 * Prints one consolidated, actionable warning for missing required SyncTrace
 * configuration before Spring starts creating beans (datasource, Google
 * clients, etc). Without this, each missing setting only surfaces one at a
 * time as a deep bean-creation stack trace, forcing a fix-and-restart cycle.
 */
public class StartupConfigEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> warnings = StartupConfigValidator.collectConfigurationWarnings(environment::getProperty);
        if (warnings.isEmpty()) {
            return;
        }

        System.err.println("===========================================================");
        System.err.println("SYNCTRACE CONFIGURATION WARNING(S) DETECTED BEFORE STARTUP:");
        for (String warning : warnings) {
            System.err.println("  - " + warning);
        }
        System.err.println("See README.md 'Backend Setup' for how to configure these (environment");
        System.err.println("variables, or backend/src/main/resources/application-secrets.properties).");
        System.err.println("===========================================================");
    }
}
