package com.ieee.evaluator.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
public class SupabaseJwtValidator {

    private final String jwtIssuer;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private JsonNode jwksCache;
    private long cacheTime;
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000; // 5 minutes

    public SupabaseJwtValidator(@Value("${app.supabase.project-url:}") String projectUrl) {
        // Supabase JWT issuer is the project URL
        this.jwtIssuer = projectUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public String validateToken(String token) throws Exception {
        if (jwtIssuer == null || jwtIssuer.isBlank()) {
            throw new IllegalStateException("Supabase JWT issuer not configured");
        }

        // Split token into parts
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        // Decode header (no Base64 URL padding needed)
        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode header = objectMapper.readTree(headerJson);

        String kid = header.get("kid").asText();
        String alg = header.get("alg").asText();

        if (!"RS256".equals(alg)) {
            throw new IllegalArgumentException("Unsupported algorithm: " + alg);
        }

        // Get JWKS
        JsonNode jwks = getJwks();
        JsonNode key = findKeyById(jwks, kid);

        if (key == null) {
            throw new IllegalArgumentException("Key not found: " + kid);
        }

        // Extract public key
        RSAPublicKey publicKey = extractPublicKey(key);

        // Verify signature
        String signingInput = parts[0] + "." + parts[1];
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(publicKey);
        sig.update(signingInput.getBytes());

        if (!sig.verify(signature)) {
            throw new SecurityException("Invalid signature");
        }

        // Decode payload
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
        JsonNode payload = objectMapper.readTree(payloadJson);

        // Verify issuer
        String issuer = payload.get("iss").asText();
        if (!jwtIssuer.equals(issuer)) {
            throw new SecurityException("Invalid issuer: " + issuer);
        }

        // Check expiration
        long exp = payload.get("exp").asLong();
        if (System.currentTimeMillis() / 1000 >= exp) {
            throw new SecurityException("Token expired");
        }

        // Extract email
        return payload.get("email").asText();
    }

    private JsonNode getJwks() throws Exception {
        long now = System.currentTimeMillis();
        if (jwksCache != null && (now - cacheTime) < CACHE_DURATION_MS) {
            return jwksCache;
        }

        String jwksUrl = jwtIssuer + "/.well-known/jwks.json";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(jwksUrl))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch JWKS: " + response.statusCode());
        }

        jwksCache = objectMapper.readTree(response.body());
        cacheTime = now;
        return jwksCache;
    }

    private JsonNode findKeyById(JsonNode jwks, String kid) {
        JsonNode keys = jwks.get("keys");
        if (keys == null || !keys.isArray()) {
            return null;
        }

        for (JsonNode key : keys) {
            if (kid.equals(key.get("kid").asText())) {
                return key;
            }
        }
        return null;
    }

    private RSAPublicKey extractPublicKey(JsonNode key) throws Exception {
        String n = key.get("n").asText();
        String e = key.get("e").asText();

        byte[] nBytes = Base64.getUrlDecoder().decode(n);
        byte[] eBytes = Base64.getUrlDecoder().decode(e);

        // Build ASN.1 encoded public key
        byte[] encodedKey = encodeRsaPublicKey(nBytes, eBytes);

        X509EncodedKeySpec spec = new X509EncodedKeySpec(encodedKey);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) factory.generatePublic(spec);
    }

    private byte[] encodeRsaPublicKey(byte[] n, byte[] e) {
        // Simple ASN.1 encoding for RSA public key
        // Sequence { INTEGER n, INTEGER e }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        
        try {
            out.write(0x30); // SEQUENCE tag
            out.write(0x82); // Length format (2 bytes)
            int totalLength = 2 + n.length + 2 + e.length;
            out.write((totalLength >> 8) & 0xFF);
            out.write(totalLength & 0xFF);
            
            // INTEGER n
            out.write(0x02); // INTEGER tag
            out.write(0x82); // Length format (2 bytes)
            out.write((n.length >> 8) & 0xFF);
            out.write(n.length & 0xFF);
            out.write(n);
            
            // INTEGER e
            out.write(0x02); // INTEGER tag
            out.write(e.length);
            out.write(e);
            
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new RuntimeException("Failed to encode public key", ex);
        }
    }
}
