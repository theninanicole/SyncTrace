package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
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
     * Team resolution logic:
     * - IMPLEMENTATION: name-based match (component.getName().contains(teamCode))
     * - SDD: resolve via sourceHistoryId → EvaluationHistory → filename → TeamCodeResolver
     * - Other doc types (SRS, SPMP, STD, PROPOSAL): no established team-resolution pattern,
     *   returns false (not team-scoped)
     * 
     * @param component The component to check
     * @param teamCode The team code to match against
     * @return true if the component belongs to the team, false otherwise
     */
    public boolean belongsToTeam(TraceComponent component, String teamCode) {
        if (component == null || teamCode == null || teamCode.isBlank()) {
            return false;
        }

        DocType docType = component.getDocType();

        // IMPLEMENTATION components: name-based matching
        // GitHub ingestion embeds teamCode in the name: "{teamCode} - {path}"
        if (docType == DocType.IMPLEMENTATION) {
            return component.getName() != null && component.getName().contains(teamCode);
        }

        // SDD components: resolve via sourceHistoryId → EvaluationHistory → filename
        if (docType == DocType.SDD) {
            // Skip manually-added components (no sourceHistoryId)
            if (component.getSourceHistoryId() == null) {
                return false;
            }

            Optional<EvaluationHistory> historyOpt = historyRepository.findById(component.getSourceHistoryId());
            if (historyOpt.isPresent()) {
                String componentTeamCode = TeamCodeResolver.extractTeamCode(historyOpt.get().getFileName());
                return componentTeamCode.equalsIgnoreCase(teamCode);
            }
            return false;
        }

        // Other doc types (SRS, SPMP, STD, PROPOSAL): no established team-resolution pattern
        // These are not team-scoped in the current system
        return false;
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
