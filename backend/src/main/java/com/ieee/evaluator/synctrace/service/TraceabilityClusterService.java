package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.ContinuityFinding.Severity;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Single source of truth for "what does this team's traceability matrix contain".
 *
 * <p>The Results matrix renders one row per goal <em>cluster</em> (a GENERAL objective plus its
 * SPECIFIC children, or an orphan SPECIFIC goal) and unions the components mapped to any member.
 * Gap detection and readiness scoring previously evaluated each goal individually and used a
 * stricter team-ownership rule, so they disagreed with what the matrix showed (e.g. a row with
 * UC-01 visible being reported as "no SRS component"). Everything that judges traceability now
 * goes through this class so the matrix, the pass/fail status and the readiness score all agree,
 * and results are deterministic for the same data.
 */
@Service
public class TraceabilityClusterService {

    /** Downstream doc types in matrix order, each with the upstream stage it is traced from. */
    private static final Map<DocType, DocType> UPSTREAM_OF = new LinkedHashMap<>();
    static {
        UPSTREAM_OF.put(DocType.SRS, DocType.PROPOSAL);
        UPSTREAM_OF.put(DocType.SDD, DocType.SRS);
        UPSTREAM_OF.put(DocType.SPMP, DocType.SRS);
        UPSTREAM_OF.put(DocType.STD, DocType.SRS);
        UPSTREAM_OF.put(DocType.IMPLEMENTATION, DocType.SDD);
    }

    public static final List<DocType> MATRIX_DOC_TYPES = List.copyOf(UPSTREAM_OF.keySet());

    private static final List<ArtifactKind> REQUIRED_SRS_KINDS =
        List.of(ArtifactKind.USE_CASE, ArtifactKind.ACTIVITY, ArtifactKind.WIREFRAME);

    /**
     * Component-to-component counterparts: every component of the key kind must have a
     * semantically matching component of each listed kind (e.g. a use case needs its own
     * class diagram, sequence diagram and test case, not just "some SDD component").
     */
    private static final Map<ArtifactKind, List<ArtifactKind>> COUNTERPART_KINDS = new LinkedHashMap<>();
    static {
        COUNTERPART_KINDS.put(ArtifactKind.USE_CASE,
            List.of(ArtifactKind.CLASS, ArtifactKind.SEQUENCE, ArtifactKind.TEST_CASE));
        COUNTERPART_KINDS.put(ArtifactKind.WIREFRAME, List.of(ArtifactKind.UI));
        COUNTERPART_KINDS.put(ArtifactKind.CLASS, List.of(ArtifactKind.IMPL_OO));
        COUNTERPART_KINDS.put(ArtifactKind.NON_OO, List.of(ArtifactKind.IMPL_NON_OO));
    }

    private static final Map<ArtifactKind, String> KIND_LABELS = Map.ofEntries(
        Map.entry(ArtifactKind.USE_CASE, "use case"),
        Map.entry(ArtifactKind.WIREFRAME, "wireframe"),
        Map.entry(ArtifactKind.CLASS, "class diagram"),
        Map.entry(ArtifactKind.SEQUENCE, "sequence diagram"),
        Map.entry(ArtifactKind.UI, "UI design"),
        Map.entry(ArtifactKind.NON_OO, "non-OO design"),
        Map.entry(ArtifactKind.TEST_CASE, "test case"),
        Map.entry(ArtifactKind.IMPL_OO, "OO implementation"),
        Map.entry(ArtifactKind.IMPL_NON_OO, "non-OO implementation"));

    private final SmartGoalRepository goalRepository;
    private final GoalComponentMappingRepository mappingRepository;
    private final TraceComponentRepository componentRepository;
    private final TeamComponentResolverService teamComponentResolver;

    public TraceabilityClusterService(
            SmartGoalRepository goalRepository,
            GoalComponentMappingRepository mappingRepository,
            TraceComponentRepository componentRepository,
            TeamComponentResolverService teamComponentResolver) {
        this.goalRepository = goalRepository;
        this.mappingRepository = mappingRepository;
        this.componentRepository = componentRepository;
        this.teamComponentResolver = teamComponentResolver;
    }

    public record GoalCluster(
            SmartGoal primary,
            List<SmartGoal> children,
            Map<DocType, List<TraceComponent>> componentsByDocType) {

        public Long id() {
            return primary.getId();
        }

        public List<Long> memberGoalIds() {
            List<Long> ids = new ArrayList<>();
            ids.add(primary.getId());
            children.forEach(child -> ids.add(child.getId()));
            return ids;
        }

        public List<TraceComponent> components(DocType docType) {
            return componentsByDocType.getOrDefault(docType, List.of());
        }

        public Set<DocType> coveredDocTypes() {
            Set<DocType> covered = EnumSet.noneOf(DocType.class);
            componentsByDocType.forEach((docType, components) -> {
                if (!components.isEmpty()) covered.add(docType);
            });
            return covered;
        }
    }

    public record TraceIssue(DocType from, DocType to, Severity severity, String description) {}

    /** Clusters for a team's goals, in the same order the Results matrix renders them. */
    public List<GoalCluster> clustersForTeam(String teamCode) {
        if (teamCode == null || teamCode.isBlank()) return List.of();
        return buildClusters(goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(teamCode.trim()), teamCode);
    }

    /** Clusters for an explicit goal list; {@code teamCode} may be null to skip ownership filtering. */
    public List<GoalCluster> buildClusters(List<SmartGoal> goals, String teamCode) {
        if (goals == null || goals.isEmpty()) return List.of();

        List<SmartGoal> ordered = new ArrayList<>(goals);
        ordered.sort(Comparator
            .comparing(SmartGoal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SmartGoal::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        // Mirrors groupGoalsIntoClusters() in frontend/src/synctrace/constants.js.
        Map<Long, SmartGoal> generalById = new LinkedHashMap<>();
        for (SmartGoal goal : ordered) {
            if (goal.getGoalKind() == SmartGoal.GoalKind.GENERAL) generalById.put(goal.getId(), goal);
        }
        Map<Long, List<SmartGoal>> childrenByPrimary = new LinkedHashMap<>();
        List<SmartGoal> primaries = new ArrayList<>();
        for (SmartGoal goal : ordered) {
            if (goal.getGoalKind() == SmartGoal.GoalKind.GENERAL) {
                if (!childrenByPrimary.containsKey(goal.getId())) {
                    childrenByPrimary.put(goal.getId(), new ArrayList<>());
                    primaries.add(goal);
                }
                continue;
            }
            SmartGoal parent = goal.getParentGoalId() != null ? generalById.get(goal.getParentGoalId()) : null;
            if (parent != null) {
                if (!childrenByPrimary.containsKey(parent.getId())) {
                    childrenByPrimary.put(parent.getId(), new ArrayList<>());
                    primaries.add(parent);
                }
                childrenByPrimary.get(parent.getId()).add(goal);
            } else {
                childrenByPrimary.put(goal.getId(), new ArrayList<>());
                primaries.add(goal);
            }
        }

        Map<Long, List<TraceComponent>> componentsByGoal = loadComponentsByGoal(
            ordered.stream().map(SmartGoal::getId).filter(Objects::nonNull).toList(), teamCode);

        List<GoalCluster> clusters = new ArrayList<>();
        for (SmartGoal primary : primaries) {
            List<SmartGoal> children = childrenByPrimary.getOrDefault(primary.getId(), List.of());
            Map<Long, TraceComponent> unique = new TreeMap<>();
            componentsByGoal.getOrDefault(primary.getId(), List.of()).forEach(c -> unique.putIfAbsent(c.getId(), c));
            for (SmartGoal child : children) {
                componentsByGoal.getOrDefault(child.getId(), List.of()).forEach(c -> unique.putIfAbsent(c.getId(), c));
            }
            Map<DocType, List<TraceComponent>> byDocType = new EnumMap<>(DocType.class);
            unique.values().forEach(component -> {
                if (component.getDocType() != null) {
                    byDocType.computeIfAbsent(component.getDocType(), ignored -> new ArrayList<>()).add(component);
                }
            });
            clusters.add(new GoalCluster(primary, List.copyOf(children), byDocType));
        }
        return clusters;
    }

    /**
     * Doc types the team has started tracing (mapped on at least one goal). These are exactly
     * the columns the Results matrix shows, so a cluster missing one of them renders a red
     * "Missing" chip and must also be reported as a gap.
     */
    public Set<DocType> establishedDocTypes(List<GoalCluster> clusters) {
        Set<DocType> established = EnumSet.noneOf(DocType.class);
        clusters.forEach(cluster -> established.addAll(cluster.coveredDocTypes()));
        established.retainAll(MATRIX_DOC_TYPES);
        return established;
    }

    /** Deterministic list of traceability issues for one cluster. */
    public List<TraceIssue> evaluate(GoalCluster cluster, Set<DocType> establishedDocTypes) {
        List<TraceIssue> issues = new ArrayList<>();

        // A goal with nothing mapped has no traceability at all; it must fail, not pass.
        if (cluster.coveredDocTypes().isEmpty()) {
            issues.add(new TraceIssue(DocType.PROPOSAL, DocType.SRS, Severity.HIGH,
                "SMART Goal has no mapped components."));
            return issues;
        }

        List<TraceComponent> srs = cluster.components(DocType.SRS);
        if (srs.isEmpty()) {
            issues.add(new TraceIssue(DocType.PROPOSAL, DocType.SRS, Severity.HIGH,
                "SMART Goal has no mapped SRS component."));
        } else {
            Set<ArtifactKind> presentKinds = EnumSet.noneOf(ArtifactKind.class);
            srs.forEach(component -> {
                if (component.getArtifactKind() != null) presentKinds.add(component.getArtifactKind());
            });
            for (ArtifactKind kind : REQUIRED_SRS_KINDS) {
                if (!presentKinds.contains(kind)) {
                    issues.add(new TraceIssue(DocType.PROPOSAL, DocType.SRS, Severity.HIGH,
                        "SMART Goal is missing required SRS " + kind + " component."));
                }
            }
        }

        for (DocType docType : MATRIX_DOC_TYPES) {
            if (docType == DocType.SRS) continue;
            if (establishedDocTypes.contains(docType) && cluster.components(docType).isEmpty()) {
                issues.add(new TraceIssue(UPSTREAM_OF.get(docType), docType, Severity.MEDIUM,
                    "SMART Goal has no mapped " + docType + " component."));
            }
        }

        checkCorrespondence(issues, cluster, DocType.SRS, DocType.SDD);
        checkCorrespondence(issues, cluster, DocType.SRS, DocType.SPMP);
        checkCorrespondence(issues, cluster, DocType.SRS, DocType.STD);
        checkCorrespondence(issues, cluster, DocType.SDD, DocType.IMPLEMENTATION);
        checkCounterparts(issues, cluster, establishedDocTypes);
        return issues;
    }

    /**
     * For each component with required counterparts, reports every counterpart kind that has
     * no semantically matching component in the cluster. Only checked for doc types the cluster
     * already covers; a wholly missing stage is reported once by the coverage check instead.
     */
    private void checkCounterparts(List<TraceIssue> issues, GoalCluster cluster, Set<DocType> establishedDocTypes) {
        Set<DocType> covered = cluster.coveredDocTypes();
        for (Map.Entry<ArtifactKind, List<ArtifactKind>> rule : COUNTERPART_KINDS.entrySet()) {
            ArtifactKind sourceKind = rule.getKey();
            DocType from = sourceKind.toDocType();
            List<TraceComponent> sources = cluster.components(from).stream()
                .filter(component -> component.getArtifactKind() == sourceKind)
                .toList();
            if (sources.isEmpty()) continue;

            for (ArtifactKind targetKind : rule.getValue()) {
                DocType to = targetKind.toDocType();
                if (!establishedDocTypes.contains(to) || !covered.contains(to)) continue;
                List<Set<String>> targetTokens = cluster.components(to).stream()
                    .filter(component -> component.getArtifactKind() == targetKind)
                    .map(TraceabilityClusterService::conceptTokens)
                    .toList();
                for (TraceComponent source : sources) {
                    Set<String> sourceTokens = conceptTokens(source);
                    boolean matched = targetTokens.stream().anyMatch(tokens -> !Collections.disjoint(sourceTokens, tokens));
                    if (!matched) {
                        issues.add(new TraceIssue(from, to, Severity.HIGH,
                            capitalize(kindLabel(sourceKind)) + " " + describe(source)
                                + " has no corresponding " + kindLabel(targetKind) + " (" + to + ")."));
                    }
                }
            }
        }
    }

    private static boolean hasCounterpartRuleFor(ArtifactKind kind, DocType to) {
        return COUNTERPART_KINDS.getOrDefault(kind, List.of()).stream()
            .anyMatch(target -> target.toDocType() == to);
    }

    private static String kindLabel(ArtifactKind kind) {
        return KIND_LABELS.getOrDefault(kind, kind.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String describe(TraceComponent component) {
        String code = component.getCodeName();
        return "'" + component.getName() + "'" + (code != null && !code.isBlank() ? " (" + code + ")" : "");
    }

    private void checkCorrespondence(List<TraceIssue> issues, GoalCluster cluster, DocType from, DocType to) {
        List<TraceComponent> sources = cluster.components(from);
        List<TraceComponent> targets = cluster.components(to);
        // A missing target stage is reported by the coverage check above, not per component.
        if (sources.isEmpty() || targets.isEmpty()) return;
        List<Set<String>> targetTokens = targets.stream().map(TraceabilityClusterService::conceptTokens).toList();
        for (TraceComponent source : sources) {
            // Components with kind-level counterparts are checked more precisely by checkCounterparts.
            if (hasCounterpartRuleFor(source.getArtifactKind(), to)) continue;
            Set<String> sourceTokens = conceptTokens(source);
            boolean matched = targetTokens.stream().anyMatch(tokens -> !Collections.disjoint(sourceTokens, tokens));
            if (!matched) {
                issues.add(new TraceIssue(from, to, Severity.HIGH,
                    "Component '" + source.getName() + "' has no semantically corresponding " + to + " component."));
            }
        }
    }

    static Set<String> conceptTokens(TraceComponent component) {
        String text = String.join(" ",
                Objects.toString(component.getName(), ""),
                Objects.toString(component.getCodeName(), ""),
                Objects.toString(component.getContent(), ""))
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ");
        Set<String> tokens = new HashSet<>();
        for (String token : text.split(" ")) {
            if (token.length() >= 4) tokens.add(token);
        }
        return tokens;
    }

    private Map<Long, List<TraceComponent>> loadComponentsByGoal(List<Long> goalIds, String teamCode) {
        if (goalIds.isEmpty()) return Map.of();
        List<GoalComponentMapping> mappings = new ArrayList<>(mappingRepository.findByGoalIdIn(goalIds));
        if (mappings.isEmpty()) return Map.of();
        mappings.sort(Comparator.comparing(GoalComponentMapping::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        Set<Long> componentIds = new TreeSet<>();
        mappings.forEach(mapping -> {
            if (mapping.getComponentId() != null) componentIds.add(mapping.getComponentId());
        });
        Map<Long, TraceComponent> componentsById = new HashMap<>();
        for (TraceComponent component : componentRepository.findAllById(componentIds)) {
            if (teamComponentResolver.countsForTeam(component, teamCode)) {
                componentsById.put(component.getId(), component);
            }
        }

        Map<Long, List<TraceComponent>> result = new HashMap<>();
        for (GoalComponentMapping mapping : mappings) {
            TraceComponent component = componentsById.get(mapping.getComponentId());
            if (component != null) {
                result.computeIfAbsent(mapping.getGoalId(), ignored -> new ArrayList<>()).add(component);
            }
        }
        return result;
    }
}
