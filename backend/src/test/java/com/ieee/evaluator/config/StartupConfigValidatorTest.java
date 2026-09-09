package com.ieee.evaluator.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupConfigValidatorTest {

    private List<String> validate(Map<String, String> properties) {
        return StartupConfigValidator.collectConfigurationWarnings(properties::get);
    }

    @Test
    void reportsAllRequiredSettingsMissingWhenNothingIsConfigured() {
        List<String> warnings = validate(new HashMap<>());

        assertEquals(4, warnings.size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("SPRING_DATASOURCE_URL")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("GOOGLE_SERVICE_ACCOUNT_JSON")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("GOOGLE_CLIENT_ID")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("GOOGLE_SHEET_ID")));
    }

    @Test
    void reportsNoWarningsWhenAllRequiredSettingsArePresent() {
        Map<String, String> properties = new HashMap<>();
        properties.put("spring.datasource.url", "jdbc:postgresql://host:5432/db");
        properties.put("app.google.service-account-json-base64", "base64content");
        properties.put("app.google.oauth.client-id", "client-id");
        properties.put("app.google.oauth.client-secret", "client-secret");
        properties.put("app.google.oauth.refresh-token", "refresh-token");
        properties.put("app.google.spreadsheet-id", "sheet-id");

        assertTrue(validate(properties).isEmpty());
    }

    @Test
    void treatsBlankValuesAsMissing() {
        Map<String, String> properties = new HashMap<>();
        properties.put("spring.datasource.url", "   ");

        List<String> warnings = validate(properties);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("SPRING_DATASOURCE_URL")));
    }

    @Test
    void oauthCredentialsAreOnlyConsideredCompleteWhenAllThreeArePresent() {
        Map<String, String> properties = new HashMap<>();
        properties.put("spring.datasource.url", "jdbc:postgresql://host:5432/db");
        properties.put("app.google.service-account-json-base64", "base64content");
        properties.put("app.google.spreadsheet-id", "sheet-id");
        properties.put("app.google.oauth.client-id", "client-id");
        properties.put("app.google.oauth.client-secret", "client-secret");
        // refresh-token intentionally missing

        List<String> warnings = validate(properties);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("GOOGLE_REFRESH_TOKEN"));
    }
}
