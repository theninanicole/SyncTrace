package com.ieee.evaluator.config;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legacy user-OAuth Drive credential. Drive access now uses the service account
 * ({@code googleCredential}). This bean remains optional for compatibility.
 */
@Configuration
public class OAuthCredentialConfig {

    @Value("${app.google.oauth.client-id:}")
    private String clientId;

    @Value("${app.google.oauth.client-secret:}")
    private String clientSecret;

    @Value("${app.google.oauth.refresh-token:}")
    private String refreshToken;

    @Bean(name = "driveOAuthCredential")
    @ConditionalOnProperty(prefix = "app.google.oauth", name = "refresh-token")
    public Credential driveOAuthCredential() {
        if (clientId == null || clientId.isBlank() ||
            clientSecret == null || clientSecret.isBlank() ||
            refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException(
                "Google OAuth credentials for Drive are missing. " +
                "Set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, and GOOGLE_REFRESH_TOKEN " +
                "as environment variables."
            );
        }

        return new GoogleCredential.Builder()
                .setTransport(new com.google.api.client.http.javanet.NetHttpTransport())
                .setJsonFactory(GsonFactory.getDefaultInstance())
                .setClientSecrets(clientId.trim(), clientSecret.trim())
                .build()
                .setFromTokenResponse(
                    new TokenResponse().setRefreshToken(refreshToken.trim())
                );
    }
}
