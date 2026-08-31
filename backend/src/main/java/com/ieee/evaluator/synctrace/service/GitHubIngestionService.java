package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.service.SystemSettingService;
import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class GitHubIngestionService {

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITHUB_RAW_BASE = "https://raw.githubusercontent.com";
    private static final int MAX_CONTENT_CHARS = 5000;
    private static final int PROGRESS_BATCH_SIZE = 10; // Emit progress every N files
    private static final int MAX_FILES_PER_INGEST = 500;
    private static final long MAX_FILE_SIZE_BYTES = 300_000; // ~300 KB; larger files are skipped, not downloaded
    private static final String RATE_LIMIT_MARKER = "GITHUB API RATE LIMIT";
    private static final String KEY_GITHUB_TOKEN = "GITHUB_TOKEN";
    private static final String KEY_FILE_EXTENSIONS = "GITHUB_FILE_EXTENSIONS";
    private static final String DEFAULT_EXTENSIONS = "java,js,jsx,py,ts,tsx,sql,html,css,json,xml,yml,yaml";

    private final SystemSettingService configService;
    private final TraceComponentRepository componentRepository;
    private final ProgressEmitter progressEmitter;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Set<String> inFlightIngestions = ConcurrentHashMap.newKeySet();

    public GitHubIngestionService(
            SystemSettingService configService,
            TraceComponentRepository componentRepository,
            ProgressEmitter progressEmitter,
            RestTemplate restTemplate) {
        this.configService = configService;
        this.componentRepository = componentRepository;
        this.progressEmitter = progressEmitter;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public List<TraceComponent> ingestRepository(String githubUrl, String teamCode, String sessionId) throws Exception {
        String runKey = buildRunKey(githubUrl, teamCode);
        
        if (!inFlightIngestions.add(runKey)) {
            throw new IllegalStateException(
                "An ingestion is already in progress for this repository and team. Please wait for it to finish.");
        }

        emit(sessionId, "RECEIVED", "Request accepted — starting GitHub repository ingestion", 5);

        try {
            List<TraceComponent> result = ingestRepositoryInternal(githubUrl, teamCode, sessionId);
            
            emit(sessionId, "COMPLETE", "GitHub ingestion complete. Ingested " + result.size() + " files.", 100);
            progressEmitter.complete(sessionId);
            
            return result;
        } catch (Exception e) {
            progressEmitter.error(sessionId, e.getMessage());
            throw e;
        } finally {
            inFlightIngestions.remove(runKey);
        }
    }

    private List<TraceComponent> ingestRepositoryInternal(String githubUrl, String teamCode, String sessionId) throws Exception {
        String[] parts = parseGitHubUrl(githubUrl);
        if (parts == null) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }

        String owner = parts[0];
        String repo = parts[1];
        String branch = parts.length > 2 ? parts[2] : null;

        // If branch not specified in URL, fetch repo's default branch
        if (branch == null || branch.isBlank()) {
            branch = getDefaultBranch(owner, repo);
        }

        log.info("Starting GitHub ingestion for {}/{} (branch: {})", owner, repo, branch);

        emit(sessionId, "LOADING", "Fetching repository tree from GitHub", 10);

        Set<String> allowedExtensions = getAllowedExtensions();
        List<TraceComponent> ingestedComponents = new ArrayList<>();

        // Fetch the recursive tree
        String treeUrl = String.format("%s/repos/%s/%s/git/trees/%s?recursive=1", GITHUB_API_BASE, owner, repo, branch);
        JsonNode treeResponse = fetchGitHubApi(treeUrl);

        if (!treeResponse.has("tree")) {
            throw new RuntimeException("Failed to fetch repository tree");
        }

        if (treeResponse.path("truncated").asBoolean(false)) {
            log.warn("GitHub tree listing for {}/{} (branch: {}) was truncated by the API; " +
                "some files in this repository will not be considered for ingestion.", owner, repo, branch);
            emit(sessionId, "WARNING",
                "This repository's file listing is too large for GitHub to return in full. Some files may be skipped.", 15);
        }

        JsonNode tree = treeResponse.get("tree");
        List<String> eligiblePaths = new ArrayList<>();
        int skippedForSize = 0;

        for (JsonNode node : tree) {
            if (!"blob".equals(node.get("type").asText())) continue;
            String path = node.get("path").asText();
            if (!shouldIncludeFile(path, allowedExtensions)) continue;

            long size = node.path("size").asLong(0);
            if (size > MAX_FILE_SIZE_BYTES) {
                skippedForSize++;
                continue;
            }
            eligiblePaths.add(path);
        }

        boolean cappedByFileLimit = eligiblePaths.size() > MAX_FILES_PER_INGEST;
        int totalFiles = Math.min(eligiblePaths.size(), MAX_FILES_PER_INGEST);
        List<String> pathsToIngest = eligiblePaths.subList(0, totalFiles);

        StringBuilder startMessage = new StringBuilder("Found " + totalFiles + " files to ingest.");
        if (skippedForSize > 0) {
            startMessage.append(" Skipped ").append(skippedForSize).append(" file(s) over ")
                .append(MAX_FILE_SIZE_BYTES / 1000).append(" KB.");
        }
        if (cappedByFileLimit) {
            startMessage.append(" Repository has more matching files than the ")
                .append(MAX_FILES_PER_INGEST).append("-file limit per ingestion; only the first ")
                .append(MAX_FILES_PER_INGEST).append(" will be ingested.");
            log.warn("GitHub ingestion for {}/{} capped at {} files ({} matched)",
                owner, repo, MAX_FILES_PER_INGEST, eligiblePaths.size());
        }
        emit(sessionId, "PROCESSING", startMessage.toString(), 20);

        int processedFiles = 0;
        boolean rateLimited = false;

        for (String path : pathsToIngest) {
            try {
                TraceComponent component = ingestFile(owner, repo, branch, path, teamCode);
                if (component != null) {
                    ingestedComponents.add(component);
                }
                processedFiles++;

                // Emit progress every PROGRESS_BATCH_SIZE files
                if (processedFiles % PROGRESS_BATCH_SIZE == 0 || processedFiles == totalFiles) {
                    int percent = 20 + (int) ((double) processedFiles / totalFiles * 70); // 20-90% range
                    emit(sessionId, "INGESTING", "Ingested " + processedFiles + " of " + totalFiles + " files", percent);
                }
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage().toUpperCase() : "";
                if (message.contains(RATE_LIMIT_MARKER)) {
                    log.warn("GitHub rate limit hit after ingesting {} of {} files for {}/{}; stopping early.",
                        processedFiles, totalFiles, owner, repo);
                    rateLimited = true;
                    break;
                }
                log.warn("Failed to ingest file {}: {}", path, e.getMessage());
            }
        }

        if (rateLimited) {
            emit(sessionId, "WARNING",
                "GitHub rate limit reached. Ingested " + processedFiles + " of " + totalFiles +
                    " files before stopping; add a GITHUB_TOKEN in System Settings and re-run to continue.", 90);
        }

        log.info("GitHub ingestion complete. Ingested {} files.", ingestedComponents.size());
        return ingestedComponents;
    }

    private void emit(String sessionId, String step, String message, int percent) {
        if (sessionId == null || sessionId.isBlank()) return;
        progressEmitter.emit(sessionId, step, message, percent);
    }

    private String buildRunKey(String githubUrl, String teamCode) {
        String normalizedUrl = githubUrl != null ? githubUrl.toLowerCase().trim() : "";
        String normalizedTeam = teamCode != null ? teamCode.toLowerCase().trim() : "";
        return normalizedUrl + "|" + normalizedTeam;
    }

    private String getDefaultBranch(String owner, String repo) throws Exception {
        String repoUrl = String.format("%s/repos/%s/%s", GITHUB_API_BASE, owner, repo);
        JsonNode repoResponse = fetchGitHubApi(repoUrl);
        
        if (repoResponse.has("default_branch")) {
            return repoResponse.get("default_branch").asText();
        }
        
        // Fallback to "main" if default_branch not found
        log.warn("default_branch not found for {}/{}, falling back to 'main'", owner, repo);
        return "main";
    }

    private TraceComponent ingestFile(String owner, String repo, String branch, String path, String teamCode) throws Exception {
        String rawUrl = String.format("%s/%s/%s/%s/%s", GITHUB_RAW_BASE, owner, repo, branch, path);
        
        HttpHeaders headers = new HttpHeaders();
        addAuthHeader(headers);
        
        HttpEntity<String> request = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(rawUrl, HttpMethod.GET, request, String.class);

        if (response.getStatusCode() == HttpStatus.FORBIDDEN) {
            throw new RuntimeException(
                RATE_LIMIT_MARKER + ": GitHub rate limit exceeded while fetching file content. " +
                "Add a GITHUB_TOKEN in System Settings for higher rate limits.");
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to fetch file content: " + response.getStatusCode());
        }

        String content = response.getBody();
        if (content == null) {
            content = "";
        }

        // Cap content size
        String excerpt = content.length() > MAX_CONTENT_CHARS 
            ? content.substring(0, MAX_CONTENT_CHARS) + "\n\n[... CONTENT TRUNCATED ...]"
            : content;

        // Dedupe by (docType, name) case-insensitively
        String componentName = teamCode + " - " + path;
        if (componentName.length() > 250) {
            componentName = componentName.substring(0, 249).trim() + "…";
        }
        Optional<TraceComponent> existing = componentRepository.findByDocTypeAndNameIgnoreCase(
            DocType.IMPLEMENTATION, componentName);
        
        if (existing.isPresent()) {
            TraceComponent found = existing.get();
            boolean dirty = false;
            if (found.getContent() == null || found.getContent().isBlank()
                || !found.getContent().equals("File: " + path + "\n\n" + excerpt)) {
                found.setContent("File: " + path + "\n\n" + excerpt);
                dirty = true;
            }
            if (found.getCodeName() == null || found.getCodeName().isBlank()) {
                found.setCodeName(ComponentCodeHelper.codeFromFilePath(path));
                dirty = true;
            }
            if (dirty) {
                return componentRepository.save(found);
            }
            log.debug("Component already exists: {}", componentName);
            return found;
        }

        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.IMPLEMENTATION);
        component.setArtifactKind(classifyImplementationKind(path));
        component.setName(componentName);
        component.setCodeName(ComponentCodeHelper.codeFromFilePath(path));
        component.setContent("File: " + path + "\n\n" + excerpt);
        component.setAiExtracted(true);
        component.setCreatedAt(LocalDateTime.now());
        
        return componentRepository.save(component);
    }

    private ArtifactKind classifyImplementationKind(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".cs") ||
            lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx") ||
            lower.endsWith(".h") || lower.endsWith(".hpp") || lower.endsWith(".py") ||
            lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".js") ||
            lower.endsWith(".jsx") || lower.endsWith(".go") || lower.endsWith(".rb") ||
            lower.endsWith(".swift") || lower.endsWith(".php")) {
            return ArtifactKind.IMPL_OO;
        }
        if (lower.endsWith(".sql") || lower.endsWith(".sh") || lower.endsWith(".bat") ||
            lower.endsWith(".ps1") || lower.endsWith(".yml") || lower.endsWith(".yaml") ||
            lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".properties") ||
            lower.endsWith(".gradle") || lower.endsWith(".md") || lower.endsWith(".txt")) {
            return ArtifactKind.IMPL_NON_OO;
        }
        return ArtifactKind.OTHER_IMPLEMENTATION;
    }

    private JsonNode fetchGitHubApi(String url) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        addAuthHeader(headers);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

        if (response.getStatusCode() == HttpStatus.FORBIDDEN) {
            throw new RuntimeException(
                RATE_LIMIT_MARKER + ": GitHub API rate limit exceeded. Please add a GITHUB_TOKEN in System Settings for higher rate limits."
            );
        }

        if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
            throw new RuntimeException(
                "GitHub repository or branch not found. Check the URL/branch (example: https://github.com/owner/repo/tree/main)."
            );
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("GitHub API request failed: " + response.getStatusCode());
        }

        return objectMapper.readTree(response.getBody());
    }

    private void addAuthHeader(HttpHeaders headers) {
        try {
            String token = configService.getValueOrNull(KEY_GITHUB_TOKEN);
            if (token != null && !token.isBlank()) {
                headers.setBearerAuth(token.trim());
            }
        } catch (Exception e) {
            // No token configured, proceed without auth
        }
    }

    private Set<String> getAllowedExtensions() {
        try {
            String extensionsStr = configService.getValueOrNull(KEY_FILE_EXTENSIONS);
            if (extensionsStr == null || extensionsStr.isBlank()) {
                extensionsStr = DEFAULT_EXTENSIONS;
            }
            Set<String> extensions = new HashSet<>();
            for (String ext : extensionsStr.split(",")) {
                extensions.add(ext.trim().toLowerCase());
            }
            return extensions;
        } catch (Exception e) {
            log.warn("Failed to read GITHUB_FILE_EXTENSIONS, using defaults: {}", e.getMessage());
            Set<String> defaults = new HashSet<>();
            for (String ext : DEFAULT_EXTENSIONS.split(",")) {
                defaults.add(ext.trim().toLowerCase());
            }
            return defaults;
        }
    }

    private boolean shouldIncludeFile(String path, Set<String> allowedExtensions) {
        String lowerPath = path.toLowerCase();
        for (String ext : allowedExtensions) {
            if (lowerPath.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }

    private String[] parseGitHubUrl(String url) {
        // Pattern: https://github.com/owner/repo or https://github.com/owner/repo/tree/branch
        Pattern pattern = Pattern.compile("github\\.com/([^/]+)/([^/]+)(?:/tree/([^/]+))?");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            String owner = matcher.group(1);
            String repo = matcher.group(2);
            String branch = matcher.group(3);
            return new String[]{owner, repo, branch};
        }
        return null;
    }
}
