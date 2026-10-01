package com.ieee.evaluator.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.model.File;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Service
public class GoogleDriveMetadataService {

    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private final GoogleCredential googleCredential;

    public GoogleDriveMetadataService(
            @Qualifier("googleCredential") GoogleCredential googleCredential) {
        this.googleCredential = googleCredential;
    }

    public String getMimeType(String fileId) throws IOException {
        if (googleCredential.getAccessToken() == null
                || googleCredential.getExpiresInSeconds() != null
                && googleCredential.getExpiresInSeconds() <= 60) {
            googleCredential.refreshToken();
        }

        String metadataUrl = "https://www.googleapis.com/drive/v3/files/"
                + fileId + "?fields=mimeType";
        HttpURLConnection connection = (HttpURLConnection) new URL(metadataUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + googleCredential.getAccessToken());
        connection.setConnectTimeout(60_000);
        connection.setReadTimeout(60_000);

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (InputStream inputStream = connection.getInputStream()) {
                File file = JSON_FACTORY.createJsonParser(inputStream).parse(File.class);
                return file.getMimeType();
            }
        }

        String responseBody = "";
        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream != null) {
                responseBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new MetadataLookupException(responseCode,
                "Drive metadata lookup failed for file id=" + fileId
                        + ". Response code: " + responseCode + ". Body: " + responseBody);
    }

    public static class MetadataLookupException extends IOException {
        private final int statusCode;

        public MetadataLookupException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
