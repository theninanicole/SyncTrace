package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service for determining which team a TraceComponent belongs to.
 * Extracts and centralizes team-scoping logic to prevent duplication across services.
 */
@Service
public class TeamComponentResolverService {

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

        if (component.getDocType() == TraceComponent.DocType.IMPLEMENTATION && component.getName() != null) {
            String name = component.getName().trim();
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

    /**
     * Filters a list of components to only include those belonging to the specified team.
     * 
     * @param components All components to filter
     * @param teamCode The team code to filter by
     * @return List of components belonging to the team
     */
    public List<TraceComponent> filterByTeam(List<TraceComponent> components, String teamCode) {
        if (components == null || teamCode == null || teamCode.isBlank()) {
            return List.of();
        }

        return components.stream()
            .filter(comp -> belongsToTeam(comp, teamCode))
            .toList();
    }
}
