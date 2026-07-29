package com.ieee.evaluator.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
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
        // Supabase JWT issuer is the project's GoTrue endpoint
        this.jwtIssuer = projectUrl.replaceAll("/+$", "") + "/auth/v1";
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

        if (!"ES256".equals(alg)) {
            throw new IllegalArgumentException("Unsupported algorithm: " + alg);
        }

        // Get JWKS
        JsonNode jwks = getJwks();
        JsonNode key = findKeyById(jwks, kid);

        if (key == null) {
            throw new IllegalArgumentException("Key not found: " + kid);
        }

        // Extract public key
        ECPublicKey publicKey = extractPublicKey(key);

        // Verify signature (JWS ES256 signatures are raw R||S, not ASN.1/DER)
        String signingInput = parts[0] + "." + parts[1];
        byte[] rawSignature = Base64.getUrlDecoder().decode(parts[2]);
        byte[] derSignature = rawToDerSignature(rawSignature);

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initVerify(publicKey);
        sig.update(signingInput.getBytes());

        if (!sig.verify(derSignature)) {
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

    private ECPublicKey extractPublicKey(JsonNode key) throws Exception {
        byte[] xBytes = Base64.getUrlDecoder().decode(key.get("x").asText());
        byte[] yBytes = Base64.getUrlDecoder().decode(key.get("y").asText());

        BigInteger x = new BigInteger(1, xBytes);
        BigInteger y = new BigInteger(1, yBytes);

        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1")); // NIST P-256, used by ES256
        ECParameterSpec ecParameterSpec = parameters.getParameterSpec(ECParameterSpec.class);

        ECPublicKeySpec pubSpec = new ECPublicKeySpec(new ECPoint(x, y), ecParameterSpec);
        KeyFactory factory = KeyFactory.getInstance("EC");
        return (ECPublicKey) factory.generatePublic(pubSpec);
    }

    // JWS ES256 signatures are the raw 64-byte concatenation of R and S (32 bytes each).
    // java.security's ECDSA verifier expects the ASN.1/DER SEQUENCE{INTEGER r, INTEGER s} encoding instead.
    private byte[] rawToDerSignature(byte[] rawSignature) throws Exception {
        int len = rawSignature.length / 2;
        byte[] r = toUnsignedInteger(Arrays.copyOfRange(rawSignature, 0, len));
        byte[] s = toUnsignedInteger(Arrays.copyOfRange(rawSignature, len, rawSignature.length));

        ByteArrayOutputStream sequence = new ByteArrayOutputStream();
        sequence.write(0x02); // INTEGER tag
        sequence.write(r.length);
        sequence.write(r);
        sequence.write(0x02); // INTEGER tag
        sequence.write(s.length);
        sequence.write(s);

        ByteArrayOutputStream der = new ByteArrayOutputStream();
        der.write(0x30); // SEQUENCE tag
        der.write(sequence.size());
        der.write(sequence.toByteArray());
        return der.toByteArray();
    }

    // Strips leading zero bytes, then re-adds a single 0x00 if the high bit is set,
    // so the value isn't misread as a negative ASN.1 INTEGER.
    private byte[] toUnsignedInteger(byte[] bytes) {
        int offset = 0;
        while (offset < bytes.length - 1 && bytes[offset] == 0) {
            offset++;
        }
        boolean needsPadding = (bytes[offset] & 0x80) != 0;
        byte[] result = new byte[bytes.length - offset + (needsPadding ? 1 : 0)];
        System.arraycopy(bytes, offset, result, needsPadding ? 1 : 0, bytes.length - offset);
        return result;
    }
}
