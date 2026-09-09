package com.ieee.evaluator.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Pure logic for detecting missing required SyncTrace configuration at startup.
 * Kept dependency-free (no Spring types) so it can be unit tested directly.
 */
public final class StartupConfigValidator {

    private StartupConfigValidator() {
    }

    public static List<String> collectConfigurationWarnings(Function<String, String> propertyResolver) {
        List<String> warnings = new ArrayList<>();

        if (isBlank(propertyResolver.apply("spring.datasource.url"))) {
            warnings.add("spring.datasource.url (env: SPRING_DATASOURCE_URL) is not set - " +
                "the backend cannot connect to its database.");
        }

        boolean hasServiceAccountJson = !isBlank(propertyResolver.apply("app.google.service-account-json"))
                || !isBlank(propertyResolver.apply("app.google.service-account-json-base64"));
        if (!hasServiceAccountJson) {
            warnings.add("Google service account credentials are not set (env: GOOGLE_SERVICE_ACCOUNT_JSON or " +
                "GOOGLE_SERVICE_ACCOUNT_JSON_BASE64) - Sheets-based roster/submission sync will fail unless " +
                "backend/src/main/resources/google-service-account.json is present.");
        }

        boolean hasOAuthCredentials = !isBlank(propertyResolver.apply("app.google.oauth.client-id"))
                && !isBlank(propertyResolver.apply("app.google.oauth.client-secret"))
                && !isBlank(propertyResolver.apply("app.google.oauth.refresh-token"));
        if (!hasOAuthCredentials) {
            warnings.add("Google OAuth Drive credentials are incomplete (env: GOOGLE_CLIENT_ID, " +
                "GOOGLE_CLIENT_SECRET, GOOGLE_REFRESH_TOKEN) - reading student submission files from Drive will fail.");
        }

        if (isBlank(propertyResolver.apply("app.google.spreadsheet-id"))) {
            warnings.add("app.google.spreadsheet-id (env: GOOGLE_SHEET_ID) is not set - " +
                "roster and submission sync will fail.");
        }

        return warnings;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
