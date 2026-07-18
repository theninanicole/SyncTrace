package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.model.GitHubRepositoryLink;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.repository.GitHubRepositoryLinkRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class SyncTraceService {

    private static final List<String> DOC_TYPES = List.of("SRS", "SDD", "SPMP", "STD", "IMPLEMENTATION");
    private static final String SOURCE_TYPE_GITHUB = "GITHUB";
    private static final String SOURCE_TYPE_EVALUATION_HISTORY = "EVALUATION_HISTORY";
    private static final String SOURCE_TYPE_MANUAL = "MANUAL";

    private final SmartGoalRepository smartGoalRepository;
    private final TraceComponentRepository traceComponentRepository;
    private final GoalComponentMappingRepository mappingRepository;
    private final EvaluationHistoryRepository evaluationHistoryRepository;
    private final GitHubRepositoryLinkRepository gitHubRepositoryLinkRepository;
    private final GitHubSourceIngestionService gitHubSourceIngestionService;

    public SyncTraceService(
        SmartGoalRepository smartGoalRepository,
        TraceComponentRepository traceComponentRepository,
        GoalComponentMappingRepository mappingRepository,
        EvaluationHistoryRepository evaluationHistoryRepository,
        GitHubRepositoryLinkRepository gitHubRepositoryLinkRepository,
        GitHubSourceIngestionService gitHubSourceIngestionService
    ) {
        this.smartGoalRepository = smartGoalRepository;
        this.traceComponentRepository = traceComponentRepository;
        this.mappingRepository = mappingRepository;
        this.evaluationHistoryRepository = evaluationHistoryRepository;
        this.gitHubRepositoryLinkRepository = gitHubRepositoryLinkRepository;
        this.gitHubSourceIngestionService = gitHubSourceIngestionService;
    }

    public List<Map<String, Object>> getSmartGoals() {
        List<SmartGoal> goals = smartGoalRepository.findAllByOrderByCreatedAtAsc();
        List<Long> goalIds = new ArrayList<>();
        for (SmartGoal goal : goals) {
            goalIds.add(goal.getId());
        }

        Map<Long, Set<String>> coverageByGoal = new HashMap<>();
        if (!goalIds.isEmpty()) {
            List<GoalComponentMapping> mappings = mappingRepository.findByGoalIdsWithComponents(goalIds);
            for (GoalComponentMapping mapping : mappings) {
                coverageByGoal
                    .computeIfAbsent(mapping.getGoal().getId(), ignored -> new HashSet<>())
                    .add(normalizeDocType(mapping.getComponent().getDocType()));
            }
        }

        List<Map<String, Object>> response = new ArrayList<>();
        for (SmartGoal goal : goals) {
            Map<String, Boolean> categoryStatus = new LinkedHashMap<>();
            Set<String> covered = coverageByGoal.getOrDefault(goal.getId(), Set.of());
            for (String dt : DOC_TYPES) {
                categoryStatus.put(dt, covered.contains(dt));
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", goal.getId());
            row.put("description", goal.getDescription());
            row.put("createdAt", goal.getCreatedAt());
            row.put("categoryStatus", categoryStatus);
            response.add(row);
        }

        return response;
    }

    public Map<String, Object> createSmartGoal(String description) {
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("description is required.");
        }

        SmartGoal goal = new SmartGoal();
        goal.setDescription(description.trim());
        SmartGoal saved = smartGoalRepository.save(goal);

        return Map.of(
            "id", saved.getId(),
            "description", saved.getDescription(),
            "createdAt", saved.getCreatedAt()
        );
    }

    public Map<String, String> deleteSmartGoal(Long goalId) {
        SmartGoal goal = smartGoalRepository.findById(goalId)
            .orElseThrow(() -> new IllegalArgumentException("Goal not found."));

        mappingRepository.deleteByGoalId(goalId);
        smartGoalRepository.delete(goal);
        return Map.of("message", "Goal deleted.");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTraceComponents(String docType, String search) {
        String normalizedDocType = normalizeNullableDocType(docType);
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

        return traceComponentRepository.findAll().stream()
            .filter(c -> normalizedDocType == null || normalizeDocType(c.getDocType()).equals(normalizedDocType))
            .filter(c -> normalizedSearch.isBlank() || c.getName().toLowerCase(Locale.ROOT).contains(normalizedSearch))
            .sorted((left, right) -> right.getId().compareTo(left.getId()))
            .map(this::toTraceComponentPayload)
            .toList();
    }

    public Map<String, Object> createTraceComponent(String docType, String name, String content) {
        String normalizedDocType = requireDocType(docType);
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name is required.");
        }

        Optional<TraceComponent> existing = traceComponentRepository.findFirstByDocTypeAndNameIgnoreCase(normalizedDocType, name.trim());
        if (existing.isPresent()) {
            return toTraceComponentPayload(existing.get());
        }

        TraceComponent component = new TraceComponent();
        component.setDocType(normalizedDocType);
        component.setName(name.trim());
        component.setContent(content == null || content.trim().isEmpty() ? null : content.trim());
        component.setAiExtracted(false);
        component.setSourceType(SOURCE_TYPE_MANUAL);
        component.setSourceCapturedAt(LocalDateTime.now());
        if (component.getContent() != null) {
            component.setSourceHash(sha256(component.getContent()));
        }

        TraceComponent saved = traceComponentRepository.save(component);
        return toTraceComponentPayload(saved);
    }

    public Map<String, Object> renameTraceComponent(Long componentId, String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name is required.");
        }

        TraceComponent component = traceComponentRepository.findById(componentId)
            .orElseThrow(() -> new IllegalArgumentException("Component not found."));

        Optional<TraceComponent> duplicate = traceComponentRepository.findFirstByDocTypeAndNameIgnoreCase(component.getDocType(), name.trim());
        if (duplicate.isPresent() && !duplicate.get().getId().equals(componentId)) {
            throw new IllegalArgumentException("Another " + component.getDocType() + " component already has that name.");
        }

        component.setName(name.trim());
        return toTraceComponentPayload(traceComponentRepository.save(component));
    }

    public Map<String, String> deleteTraceComponent(Long componentId) {
        TraceComponent component = traceComponentRepository.findById(componentId)
            .orElseThrow(() -> new IllegalArgumentException("Component not found."));

        mappingRepository.deleteByComponentId(componentId);
        traceComponentRepository.delete(component);
        return Map.of("message", "Component deleted.");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTraceComponent(Long componentId) {
        TraceComponent component = traceComponentRepository.findById(componentId)
            .orElseThrow(() -> new IllegalArgumentException("Component not found."));
        return toTraceComponentPayload(component);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getGoalComponents(Long goalId) {
        if (!smartGoalRepository.existsById(goalId)) {
            throw new IllegalArgumentException("Goal not found.");
        }

        return mappingRepository.findByGoalIdWithComponents(goalId).stream()
            .map(mapping -> mapping.getComponent())
            .map(this::toComponentSummary)
            .toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, List<Map<String, Object>>> getAllGoalComponents() {
        List<SmartGoal> goals = smartGoalRepository.findAllByOrderByCreatedAtAsc();
        if (goals.isEmpty()) {
            return Map.of();
        }

        List<Long> goalIds = new ArrayList<>();
        for (SmartGoal goal : goals) {
            goalIds.add(goal.getId());
        }
        List<GoalComponentMapping> mappings = mappingRepository.findByGoalIdsWithComponents(goalIds);

        Map<Long, List<Map<String, Object>>> byGoal = new LinkedHashMap<>();
        for (GoalComponentMapping mapping : mappings) {
            byGoal.computeIfAbsent(mapping.getGoal().getId(), ignored -> new ArrayList<>())
                .add(toComponentSummary(mapping.getComponent()));
        }

        return byGoal;
    }

    public Map<String, Integer> addGoalComponents(Long goalId, List<Long> componentIds) {
        if (componentIds == null || componentIds.isEmpty()) {
            return Map.of("added", 0);
        }

        SmartGoal goal = smartGoalRepository.findById(goalId)
            .orElseThrow(() -> new IllegalArgumentException("Goal not found."));

        int added = 0;
        for (Long componentId : componentIds.stream().filter(Objects::nonNull).collect(Collectors.toSet())) {
            TraceComponent component = traceComponentRepository.findById(componentId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));

            if (mappingRepository.findByGoalIdAndComponentId(goalId, componentId).isPresent()) {
                continue;
            }

            GoalComponentMapping mapping = new GoalComponentMapping();
            mapping.setGoal(goal);
            mapping.setComponent(component);
            mappingRepository.save(mapping);
            added++;
        }

        return Map.of("added", added);
    }

    public Map<String, String> removeGoalComponent(Long goalId, Long componentId) {
        GoalComponentMapping mapping = mappingRepository.findByGoalIdAndComponentId(goalId, componentId)
            .orElse(null);

        if (mapping != null) {
            mappingRepository.delete(mapping);
        }

        return Map.of("message", "Mapping removed.");
    }

    public Map<String, Object> extractTraceComponents(Long historyId) {
        EvaluationHistory history = evaluationHistoryRepository.findById(historyId)
            .orElseThrow(() -> new IllegalArgumentException("Evaluation history not found."));

        String primaryDocType = inferDocType(history.getFileName());
        String fallbackDocType = "IMPLEMENTATION".equals(primaryDocType) ? "SDD" : "IMPLEMENTATION";

        List<TraceComponent> extracted = new ArrayList<>();
        extracted.add(createExtractedComponent(history, primaryDocType, "Auto-extracted " + primaryDocType + " component"));
        extracted.add(createExtractedComponent(history, fallbackDocType, "Auto-extracted " + fallbackDocType + " component"));

        List<Map<String, Object>> payload = extracted.stream().map(this::toTraceComponentPayload).toList();
        return Map.of("components", payload, "count", payload.size());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getGitHubRepositoryLinks() {
        return gitHubRepositoryLinkRepository.findAllByOrderByCreatedAtDesc().stream()
            .map(this::toGitHubRepositoryPayload)
            .toList();
    }

    public Map<String, Object> linkGitHubRepository(String repositoryUrl, String defaultBranch) {
        GitHubSourceIngestionService.ParsedRepository parsed = gitHubSourceIngestionService.parseRepositoryUrl(repositoryUrl);
        String branch = (defaultBranch == null || defaultBranch.trim().isEmpty()) ? "main" : defaultBranch.trim();

        GitHubRepositoryLink repository = gitHubRepositoryLinkRepository
            .findByOwnerAndRepo(parsed.owner(), parsed.repo())
            .orElseGet(GitHubRepositoryLink::new);

        repository.setOwner(parsed.owner());
        repository.setRepo(parsed.repo());
        repository.setRepositoryUrl(parsed.normalizedUrl());
        repository.setDefaultBranch(branch);
        repository.setActive(true);

        GitHubRepositoryLink saved = gitHubRepositoryLinkRepository.save(repository);
        return toGitHubRepositoryPayload(saved);
    }

    public Map<String, Object> ingestGitHubRepository(Long repositoryId, String accessToken, Integer maxFiles) {
        GitHubRepositoryLink repository = gitHubRepositoryLinkRepository.findById(repositoryId)
            .orElseThrow(() -> new IllegalArgumentException("Repository link not found."));

        int requestedMaxFiles = (maxFiles == null ? 80 : maxFiles);
        List<GitHubSourceIngestionService.GitHubSourceFile> files = gitHubSourceIngestionService.ingestRepository(
            repository.getOwner(),
            repository.getRepo(),
            repository.getDefaultBranch(),
            accessToken,
            requestedMaxFiles
        );

        int added = 0;
        int skippedDuplicates = 0;

        for (GitHubSourceIngestionService.GitHubSourceFile file : files) {
            if (traceComponentRepository.existsBySourceTypeAndSourceHash(SOURCE_TYPE_GITHUB, file.sourceHash())) {
                skippedDuplicates++;
                continue;
            }

            TraceComponent component = new TraceComponent();
            component.setDocType("IMPLEMENTATION");
            component.setName(file.path());
            component.setContent(file.normalizedContent());
            component.setAiExtracted(false);
            component.setSourceType(SOURCE_TYPE_GITHUB);
            component.setSourceRef(file.path());
            component.setSourceUrl(file.htmlUrl());
            component.setSourceHash(file.sourceHash());
            component.setSourceCapturedAt(LocalDateTime.now());
            traceComponentRepository.save(component);
            added++;
        }

        repository.setLastIngestedAt(LocalDateTime.now());
        gitHubRepositoryLinkRepository.save(repository);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repository", toGitHubRepositoryPayload(repository));
        result.put("scanned", files.size());
        result.put("added", added);
        result.put("skippedDuplicates", skippedDuplicates);
        return result;
    }

    private TraceComponent createExtractedComponent(EvaluationHistory history, String docType, String titlePrefix) {
        TraceComponent component = new TraceComponent();
        component.setDocType(docType);
        component.setName(titlePrefix + " #" + history.getId());
        String extractedContent = buildExtractedContent(history);
        component.setContent(extractedContent);
        component.setSourceHistoryId(history.getId());
        component.setAiExtracted(true);
        component.setSourceType(SOURCE_TYPE_EVALUATION_HISTORY);
        component.setSourceRef(history.getFileName());
        component.setSourceHash(sha256(extractedContent));
        component.setSourceCapturedAt(LocalDateTime.now());
        return traceComponentRepository.save(component);
    }

    private String buildExtractedContent(EvaluationHistory history) {
        String fileName = history.getFileName() == null ? "submission" : history.getFileName();
        String result = history.getEvaluationResult();
        if (result == null || result.isBlank()) {
            return "Extracted from evaluated submission: " + fileName + ".";
        }

        String compact = result.replaceAll("\\s+", " ").trim();
        if (compact.length() > 400) {
            compact = compact.substring(0, 400) + "...";
        }

        return "Extracted from evaluated submission: " + fileName + "\n\n" + compact;
    }

    private String inferDocType(String fileName) {
        if (fileName == null) return "IMPLEMENTATION";

        String upper = fileName.toUpperCase(Locale.ROOT);
        if (upper.contains("SRS")) return "SRS";
        if (upper.contains("SDD")) return "SDD";
        if (upper.contains("SPMP")) return "SPMP";
        if (upper.contains("STD")) return "STD";
        return "IMPLEMENTATION";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash source content.", ex);
        }
    }

    private Map<String, Object> toComponentSummary(TraceComponent component) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", component.getId());
        payload.put("docType", normalizeDocType(component.getDocType()));
        payload.put("name", component.getName());
        payload.put("sourceHistoryId", component.getSourceHistoryId());
        payload.put("sourceType", component.getSourceType());
        payload.put("sourceRef", component.getSourceRef());
        payload.put("sourceUrl", component.getSourceUrl());
        payload.put("sourceHash", component.getSourceHash());
        payload.put("sourceCapturedAt", component.getSourceCapturedAt());
        payload.put("createdAt", component.getCreatedAt());
        return payload;
    }

    private Map<String, Object> toTraceComponentPayload(TraceComponent component) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", component.getId());
        payload.put("docType", normalizeDocType(component.getDocType()));
        payload.put("name", component.getName());
        payload.put("content", component.getContent());
        payload.put("sourceHistoryId", component.getSourceHistoryId());
        payload.put("sourceType", component.getSourceType());
        payload.put("sourceRef", component.getSourceRef());
        payload.put("sourceUrl", component.getSourceUrl());
        payload.put("sourceHash", component.getSourceHash());
        payload.put("sourceCapturedAt", component.getSourceCapturedAt());
        payload.put("imageData", component.getImageData());
        payload.put("aiExtracted", Boolean.TRUE.equals(component.getAiExtracted()));
        payload.put("createdAt", component.getCreatedAt());
        return payload;
    }

    private Map<String, Object> toGitHubRepositoryPayload(GitHubRepositoryLink repository) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", repository.getId());
        payload.put("owner", repository.getOwner());
        payload.put("repo", repository.getRepo());
        payload.put("repositoryUrl", repository.getRepositoryUrl());
        payload.put("defaultBranch", repository.getDefaultBranch());
        payload.put("active", repository.getActive());
        payload.put("lastIngestedAt", repository.getLastIngestedAt());
        payload.put("createdAt", repository.getCreatedAt());
        payload.put("updatedAt", repository.getUpdatedAt());
        return payload;
    }

    private String requireDocType(String docType) {
        String normalized = normalizeNullableDocType(docType);
        if (normalized == null || !DOC_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Invalid docType.");
        }
        return normalized;
    }

    private String normalizeNullableDocType(String docType) {
        if (docType == null || docType.trim().isEmpty() || "ALL".equalsIgnoreCase(docType.trim())) {
            return null;
        }
        return normalizeDocType(docType);
    }

    private String normalizeDocType(String docType) {
        return docType == null ? "" : docType.trim().toUpperCase(Locale.ROOT);
    }
}
