package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.model.EvaluationHistoryFileNameDTO;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for determining which team a TraceComponent belongs to.
 * Extracts and centralizes team-scoping logic to prevent duplication across services.
 */
@Service
public class TeamComponentResolverService {

    private static final String MANUAL_TEAM_REF_PREFIX = "team:";

    private final TraceComponentRepository componentRepository;
    private final EvaluationHistoryRepository historyRepository;

    public TeamComponentResolverService(
            TraceComponentRepository componentRepository,
            EvaluationHistoryRepository historyRepository) {
        this.componentRepository = componentRepository;
        this.historyRepository = historyRepository;
    }

    /**
     * Determines if a component belongs to the specified team.
     *
     * @param component The component to check
     * @param teamCode The team code to match against
     * @return true if the component belongs to the team, false otherwise
     */
    public boolean belongsToTeam(TraceComponent component, String teamCode) {
        if (component == null || teamCode == null || teamCode.isBlank()) {
            return false;
        }

        return resolveTeamCode(component)
            .map(resolvedTeamCode -> resolvedTeamCode.equalsIgnoreCase(teamCode))
            .orElse(false);
    }

    /**
     * Whether a component that is already mapped to one of this team's goals should count
     * as this team's evidence. Unlike {@link #belongsToTeam}, a component whose owner cannot
     * be resolved (e.g. one added manually on the mapping page) counts, because the mapping
     * itself was made on a team-scoped goal. Only components provably owned by a different
     * team are excluded. The Results matrix, gap detection and readiness score all use this
     * same rule so they never disagree about what a cell contains.
     */
    public boolean countsForTeam(TraceComponent component, String teamCode) {
        if (component == null) return false;
        if (teamCode == null || teamCode.isBlank()) return true;
        return resolveTeamCode(component)
            .map(resolvedTeamCode -> resolvedTeamCode.equalsIgnoreCase(teamCode.trim()))
            .orElse(true);
    }

    /**
     * Resolves the team code for a component when possible.
     *
     * Resolution order:
     * - Any component sourced from EvaluationHistory uses the submission filename team code.
     * - Manually added components carry their team in sourceRef: "team:{teamCode}".
     * - IMPLEMENTATION components use the GitHub ingestion naming convention: "{teamCode} - {path}".
     * - Components without either source cannot be safely team-scoped.
     */
    public Optional<String> resolveTeamCode(TraceComponent component) {
        if (component == null) {
            return Optional.empty();
        }

        if (component.getSourceHistoryId() != null) {
            return historyRepository.findById(component.getSourceHistoryId())
                .map(EvaluationHistory::getFileName)
                .map(TeamCodeResolver::extractTeamCode)
                .filter(teamCode -> !teamCode.isBlank());
        }

        return teamCodeFromOrigin(component.getSourceRef(), component.getDocType(), component.getName());
    }

    /** The sourceRef that ties a manually added component to its team. */
    public static String manualTeamRef(String teamCode) {
        return MANUAL_TEAM_REF_PREFIX + teamCode;
    }

    /**
     * Loads only the components of one team, so callers never read other teams' rows.
     * Follows the same ownership rule as {@link #belongsToTeam}.
     */
    public List<TraceComponent> findTeamComponents(String teamCode) {
        if (teamCode == null || teamCode.isBlank()) {
            return List.of();
        }

        Set<Long> historyIds = historyIdsForTeam(teamCode);
        return componentRepository.findTeamCandidates(historyIds, teamCode).stream()
            .filter(c -> ownedByTeam(
                c.getSourceHistoryId(), c.getSourceRef(), c.getDocType(), c.getName(), historyIds, teamCode))
            .toList();
    }

    /** {@link #findTeamComponents} for list views, which don't need the page images. */
    public List<TraceComponentSummaryDTO> findTeamSummaries(String teamCode, DocType docType, String search) {
        if (teamCode == null || teamCode.isBlank()) {
            return List.of();
        }

        Set<Long> historyIds = historyIdsForTeam(teamCode);
        return componentRepository.findTeamCandidateSummaries(historyIds, teamCode, docType, search).stream()
            .filter(s -> ownedByTeam(
                s.sourceHistoryId(), s.sourceRef(), s.docType(), s.name(), historyIds, teamCode))
            .toList();
    }

    private Set<Long> historyIdsForTeam(String teamCode) {
        return historyRepository.findFileNamesContaining(teamCode).stream()
            .filter(history -> TeamCodeResolver.extractTeamCode(history.fileName()).equalsIgnoreCase(teamCode))
            .map(EvaluationHistoryFileNameDTO::id)
            .collect(Collectors.toSet());
    }

    private static boolean ownedByTeam(
            Long sourceHistoryId, String sourceRef, DocType docType, String name,
            Set<Long> teamHistoryIds, String teamCode) {
        if (sourceHistoryId != null) {
            return teamHistoryIds.contains(sourceHistoryId);
        }
        return teamCodeFromOrigin(sourceRef, docType, name)
            .map(resolvedTeamCode -> resolvedTeamCode.equalsIgnoreCase(teamCode))
            .orElse(false);
    }

    private static Optional<String> teamCodeFromOrigin(String sourceRef, DocType docType, String componentName) {
        if (sourceRef != null && sourceRef.startsWith(MANUAL_TEAM_REF_PREFIX)) {
            return Optional.of(sourceRef.substring(MANUAL_TEAM_REF_PREFIX.length()));
        }
        return teamCodeFromName(docType, componentName);
    }

    private static Optional<String> teamCodeFromName(DocType docType, String componentName) {
        if (docType == DocType.IMPLEMENTATION && componentName != null) {
            String name = componentName.trim();
            int separatorIndex = name.indexOf(" - ");
            if (separatorIndex > 0) {
                String teamCode = name.substring(0, separatorIndex).trim();
                if (!teamCode.isBlank()) {
                    return Optional.of(teamCode);
                }
            }
        }

        return Optional.empty();
    }
}
