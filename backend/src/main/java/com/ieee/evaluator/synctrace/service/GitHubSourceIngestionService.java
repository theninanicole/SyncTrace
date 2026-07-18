package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubSourceIngestionService {

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile("^https?://github\\.com/([^/]+)/([^/#?]+)(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        ".java", ".kt", ".groovy", ".scala", ".js", ".jsx", ".ts", ".tsx", ".py", ".go", ".cs", ".cpp", ".c", ".h"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GitHubSourceIngestionService() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    public ParsedRepository parseRepositoryUrl(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("repositoryUrl is required.");
        }

        String normalized = repositoryUrl.trim();
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        Matcher matcher = GITHUB_URL_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid GitHub repository URL.");
        }

        String owner = matcher.group(1);
        String repo = matcher.group(2);
        return new ParsedRepository(owner, repo, "https://github.com/" + owner + "/" + repo);
    }

    public List<GitHubSourceFile> ingestRepository(String owner, String repo, String branch, String accessToken, int maxFiles) {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("owner and repo are required.");
        }

        String resolvedBranch = (branch == null || branch.isBlank()) ? "main" : branch.trim();
        int boundedMaxFiles = Math.max(1, Math.min(maxFiles, 300));

        JsonNode treeResponse = getJson(
            "https://api.github.com/repos/" + owner + "/" + repo + "/git/trees/" + resolvedBranch + "?recursive=1",
            accessToken
        );

        JsonNode tree = treeResponse.path("tree");
        if (!tree.isArray()) {
            throw new IllegalStateException("GitHub tree response was invalid.");
        }

        List<GitHubSourceFile> files = new ArrayList<>();
        for (JsonNode item : tree) {
            if (files.size() >= boundedMaxFiles) {
                break;
            }

            if (!"blob".equalsIgnoreCase(item.path("type").asText())) {
                continue;
            }

            String path = item.path("path").asText("");
            if (!isAllowedPath(path)) {
                continue;
            }

            String blobApiUrl = item.path("url").asText("");
            if (blobApiUrl.isBlank()) {
                continue;
            }

            JsonNode blob = getJson(blobApiUrl, accessToken);
            String encoding = blob.path("encoding").asText("");
            String encodedContent = blob.path("content").asText("");

            if (!"base64".equalsIgnoreCase(encoding) || encodedContent.isBlank()) {
                continue;
            }

            String rawText = decodeBase64Content(encodedContent);
            if (rawText == null || rawText.indexOf('\u0000') >= 0) {
                continue;
            }

            String normalizedContent = preprocessSource(path, rawText);
            if (normalizedContent.length() < 20) {
                continue;
            }

            String sourceHash = sha256(normalizedContent);
            String htmlUrl = "https://github.com/" + owner + "/" + repo + "/blob/" + resolvedBranch + "/" + path;
            files.add(new GitHubSourceFile(path, htmlUrl, normalizedContent, sourceHash));
        }

        return files;
    }

    private JsonNode getJson(String url, String accessToken) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();

            if (accessToken != null && !accessToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + accessToken.trim());
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GitHub API request failed with status " + response.statusCode());
            }

            return objectMapper.readTree(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("GitHub API request failed: " + ex.getMessage(), ex);
        }
    }

    private boolean isAllowedPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("/node_modules/") || lower.contains("/dist/") || lower.contains("/build/") || lower.contains("/target/")) {
            return false;
        }

        for (String extension : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private String decodeBase64Content(String encodedContent) {
        try {
            String sanitized = encodedContent.replace("\n", "").replace("\r", "");
            byte[] decoded = Base64.getDecoder().decode(sanitized);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String preprocessSource(String path, String raw) {
        String lower = path.toLowerCase(Locale.ROOT);
        String content = raw;

        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".groovy") || lower.endsWith(".scala")
            || lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx")
            || lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".h") || lower.endsWith(".cs") || lower.endsWith(".go")) {
            content = content.replaceAll("(?s)/\\*.*?\\*/", " ");
            content = content.replaceAll("(?m)//.*$", " ");
        }

        if (lower.endsWith(".py")) {
            content = content.replaceAll("(?m)#.*$", " ");
        }

        content = content.replaceAll("\\s+", " ").trim();
        if (content.length() > 8000) {
            return content.substring(0, 8000);
        }
        return content;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash source content.", ex);
        }
    }

    public record ParsedRepository(String owner, String repo, String normalizedUrl) {
    }

    public record GitHubSourceFile(String path, String htmlUrl, String normalizedContent, String sourceHash) {
    }
}
