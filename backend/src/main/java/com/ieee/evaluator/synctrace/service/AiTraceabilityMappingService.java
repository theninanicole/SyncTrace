package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping.MappingSource;
import com.ieee.evaluator.synctrace.model.MappingStage;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiTraceabilityMappingService {

    private final SmartGoalRepository goalRepository;
    private final TraceComponentRepository componentRepository;
    private final EvaluationHistoryRepository evaluationHistoryRepository;
    private final TeamComponentResolverService teamComponentResolver;
    private final SmartGoalService goalService;
    private final Map<String, AiProvider> providers;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiTraceabilityMappingService(
            SmartGoalRepository goalRepository,
            TraceComponentRepository componentRepository,
            EvaluationHistoryRepository evaluationHistoryRepository,
            TeamComponentResolverService teamComponentResolver,
            SmartGoalService goalService,
            List<AiProvider> providerList) {
        this.goalRepository = goalRepository;
        this.componentRepository = componentRepository;
        this.evaluationHistoryRepository = evaluationHistoryRepository;
        this.teamComponentResolver = teamComponentResolver;
        this.goalService = goalService;
        this.providers = providerList.stream()
                .collect(Collectors.toMap(p -> p.getProviderName().toLowerCase(), Function.identity()));
    }

    @Transactional
    public Map<String, Object> generateMappings(
            String teamCode, MappingStage stage, String aiModel, boolean sentOnly) throws Exception {
        AiProvider provider = resolveProvider(aiModel);

        List<SmartGoal> goals = goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtDesc(teamCode);
        if (goals.isEmpty()) {
            return Map.of("createdLinks", 0, "message", "No SMART goals found for this team yet.");
        }

        List<TraceComponent> candidates = componentRepository
                .findByDocTypeOrderByCreatedAtDesc(stage.getTargetDocType());
        candidates = teamComponentResolver.filterByTeam(candidates, teamCode);

        if (sentOnly) {
            candidates = filterToSentComponents(candidates);
        }

        if (candidates.isEmpty()) {
            String message = sentOnly
                    ? "No sent " + stage.getTargetDocType() + " documents are available for this team yet."
                    : "No " + stage.getTargetDocType() + " components found for this team yet.";
            return Map.of("createdLinks", 0, "message", message);
        }

        String response = provider.complete(buildPrompt(goals, candidates, stage));
        return applyMappings(response, goals, candidates);
    }

    private List<TraceComponent> filterToSentComponents(List<TraceComponent> components) {
        Set<Long> historyIds = components.stream()
                .map(TraceComponent::getSourceHistoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (historyIds.isEmpty()) {
            return List.of();
        }

        Set<Long> sentHistoryIds = evaluationHistoryRepository.findAllById(historyIds).stream()
                .filter(history -> Boolean.TRUE.equals(history.getIsSent()))
                .map(EvaluationHistory::getId)
                .collect(Collectors.toSet());

        return components.stream()
                .filter(component -> component.getSourceHistoryId() != null
                        && sentHistoryIds.contains(component.getSourceHistoryId()))
                .toList();
    }

    private String buildPrompt(List<SmartGoal> goals, List<TraceComponent> candidates, MappingStage stage) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are mapping SMART project goals to the ").append(stage.getTargetDocType())
                .append(" diagram/component that satisfies each goal, for a software traceability matrix.\n\n");

        prompt.append("GOALS:\n");
        for (SmartGoal goal : goals) {
            prompt.append(String.format("- id=%d [%s] %s%n", goal.getId(), goal.getGoalKind(), goal.getDescription()));
        }

        prompt.append("\nCANDIDATE COMPONENTS:\n");
        for (TraceComponent component : candidates) {
            prompt.append(String.format("- id=%d code=%s name=%s kind=%s%n",
                    component.getId(), component.getCodeName(), component.getName(), component.getArtifactKind()));
        }

        prompt.append("\nFor each goal, decide which of the candidate components (zero or more) actually trace to it. ");
        prompt.append("A goal may map to multiple components; a component may satisfy multiple goals.\n");
        prompt.append("Return your response as a raw JSON array of objects, one per goal that has at least one match. Each object:\n");
        prompt.append("- \"goalId\": the goal's id (number)\n");
        prompt.append("- \"componentIds\": array of matching component ids (numbers)\n");
        prompt.append("Do not include markdown code fences, just the raw JSON array. Omit goals with no matches entirely.\n");
        return prompt.toString();
    }

    @Transactional
    private Map<String, Object> applyMappings(
            String aiResponse, List<SmartGoal> goals, List<TraceComponent> candidates) throws Exception {
        Set<Long> validGoalIds = goals.stream().map(SmartGoal::getId).collect(Collectors.toSet());
        Set<Long> validComponentIds = candidates.stream().map(TraceComponent::getId).collect(Collectors.toSet());

        JsonNode root = objectMapper.readTree(stripJsonCodeFences(aiResponse));
        if (!root.isArray()) {
            throw new IllegalStateException("Expected a JSON array from the AI mapping response.");
        }

        int createdLinks = 0;
        int goalsTouched = 0;
        Set<String> seenPairs = new HashSet<>();

        for (JsonNode entry : root) {
            long goalId = entry.path("goalId").asLong(-1);
            if (!validGoalIds.contains(goalId)) {
                continue;
            }

            List<Long> componentIds = new ArrayList<>();
            for (JsonNode idNode : entry.path("componentIds")) {
                long componentId = idNode.asLong(-1);
                if (validComponentIds.contains(componentId) && seenPairs.add(goalId + ":" + componentId)) {
                    componentIds.add(componentId);
                }
            }
            if (componentIds.isEmpty()) {
                continue;
            }

            goalService.addGoalComponents(goalId, componentIds, MappingSource.AI_SUGGESTED);
            createdLinks += componentIds.size();
            goalsTouched++;
        }

        return Map.of("createdLinks", createdLinks, "goalsTouched", goalsTouched);
    }

    private AiProvider resolveProvider(String aiModel) {
        String key = (aiModel == null || aiModel.isBlank() || "auto".equalsIgnoreCase(aiModel))
                ? "openai"
                : aiModel.toLowerCase();
        if ("gpt".equals(key)) {
            key = "openai";
        }
        AiProvider provider = providers.get(key);
        if (provider == null) {
            throw new IllegalStateException(
                    "No AI provider found for key '" + aiModel + "'. Available providers: " + providers.keySet());
        }
        log.info("Resolved AI provider: {} (requested: {})", provider.getProviderName(), aiModel);
        return provider;
    }

    private static String stripJsonCodeFences(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstNewline = trimmed.indexOf('\n');
        String withoutOpeningFence = firstNewline != -1 ? trimmed.substring(firstNewline + 1) : trimmed;
        int lastFence = withoutOpeningFence.lastIndexOf("```");
        return (lastFence != -1 ? withoutOpeningFence.substring(0, lastFence) : withoutOpeningFence).trim();
    }
}
