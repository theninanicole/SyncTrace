package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import com.ieee.evaluator.synctrace.repository.MappingSuggestionRepository;
import com.ieee.evaluator.synctrace.validation.config.ValidationProperties;
import com.ieee.evaluator.synctrace.validation.model.*;
import com.ieee.evaluator.synctrace.validation.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single implementation of the validation-summary math (page, exports and the
 * validation_team_summary table all use it). Targets come only from {@link ValidationProperties}.
 *
 * <ul>
 *   <li>Timed metrics: count, median, max, min over every recorded run; a run is within target
 *       only if it succeeded AND took at most the target. Met = % within target is at least
 *       {@code required-pct-within-target}.</li>
 *   <li>M4 uses finalized sessions' active time; open sessions are counted but not timed.</li>
 *   <li>M6 run = all steps sharing an analysis_run_id (gap detection + recommendations);
 *       duration = last step end - first step start; failed if any step failed.</li>
 *   <li>M5 rate = (accepted + edited) / (accepted + edited + user-rejected).</li>
 *   <li>M7 coverage = gaps with both / non-provisional gaps, over runs that had any gaps.</li>
 * </ul>
 */
@Service
public class ValidationSummaryService {

    private final ValidationEventRepository eventRepository;
    private final StageSessionRepository stageSessionRepository;
    private final MappingSuggestionRepository suggestionRepository;
    private final AnalysisCoverageRepository coverageRepository;
    private final TeamSummaryRowRepository summaryRowRepository;
    private final ValidationProperties properties;
    private final ZoneId serverZone;

    @Autowired
    public ValidationSummaryService(ValidationEventRepository eventRepository,
            StageSessionRepository stageSessionRepository,
            MappingSuggestionRepository suggestionRepository,
            AnalysisCoverageRepository coverageRepository,
            TeamSummaryRowRepository summaryRowRepository,
            ValidationProperties properties) {
        this(eventRepository, stageSessionRepository, suggestionRepository, coverageRepository,
            summaryRowRepository, properties, ZoneId.systemDefault());
    }

    ValidationSummaryService(ValidationEventRepository eventRepository,
            StageSessionRepository stageSessionRepository,
            MappingSuggestionRepository suggestionRepository,
            AnalysisCoverageRepository coverageRepository,
            TeamSummaryRowRepository summaryRowRepository,
            ValidationProperties properties, ZoneId serverZone) {
        this.eventRepository = eventRepository;
        this.stageSessionRepository = stageSessionRepository;
        this.suggestionRepository = suggestionRepository;
        this.coverageRepository = coverageRepository;
        this.summaryRowRepository = summaryRowRepository;
        this.properties = properties;
        this.serverZone = serverZone;
    }

    public ValidationProperties.Targets targets() {
        return properties.targets();
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ValidationDataset load(ValidationFilter filter) {
        // mapping_suggestions keeps server-local LocalDateTime (existing column type).
        LocalDateTime localFrom = LocalDateTime.ofInstant(filter.from(), serverZone);
        LocalDateTime localTo = LocalDateTime.ofInstant(filter.to(), serverZone);
        return new ValidationDataset(
            eventRepository.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(
                filter.from(), filter.to()).stream().filter(e -> filter.includesTeam(e.getTeamId())).toList(),
            stageSessionRepository.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(
                filter.from(), filter.to()).stream().filter(s -> filter.includesTeam(s.getTeamId())).toList(),
            suggestionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(
                localFrom, localTo).stream().filter(s -> filter.includesTeam(s.getTeamCode())).toList(),
            coverageRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(
                filter.from(), filter.to()).stream().filter(c -> filter.includesTeam(c.getTeamId())).toList());
    }

    private ValidationDataset loadAllTimeForTeam(String teamId) {
        return new ValidationDataset(
            eventRepository.findByTeamIdIgnoreCaseOrderByStartedAtAscIdAsc(teamId),
            stageSessionRepository.findByTeamIdIgnoreCaseOrderByStartedAtAscIdAsc(teamId),
            suggestionRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(teamId),
            coverageRepository.findByTeamIdIgnoreCaseOrderByCreatedAtAscIdAsc(teamId));
    }

    @Transactional(readOnly = true)
    public List<TeamValidationSummary> summarize(ValidationFilter filter) {
        return summarize(filter, load(filter));
    }

    // ── Math ────────────────────────────────────────────────────────────────

    /** Per-team summaries for an already-loaded dataset (pure; no DB access). */
    public List<TeamValidationSummary> summarize(ValidationFilter filter, ValidationDataset data) {
        Map<String, String> displayNames = new TreeMap<>();
        if (!filter.allTeams()) {
            filter.teamIds().forEach(team -> displayNames.putIfAbsent(ValidationFilter.key(team), team.trim()));
        } else {
            data.events().forEach(e -> addTeam(displayNames, e.getTeamId()));
            data.stageSessions().forEach(s -> addTeam(displayNames, s.getTeamId()));
            data.suggestions().forEach(s -> addTeam(displayNames, s.getTeamCode()));
            data.coverage().forEach(c -> addTeam(displayNames, c.getTeamId()));
        }

        Map<String, List<ValidationEvent>> events = groupByTeam(data.events(), ValidationEvent::getTeamId);
        Map<String, List<StageSession>> sessions = groupByTeam(data.stageSessions(), StageSession::getTeamId);
        Map<String, List<MappingSuggestion>> suggestions = groupByTeam(data.suggestions(), MappingSuggestion::getTeamCode);
        Map<String, List<AnalysisCoverage>> coverage = groupByTeam(data.coverage(), AnalysisCoverage::getTeamId);

        List<TeamValidationSummary> result = new ArrayList<>();
        for (Map.Entry<String, String> team : displayNames.entrySet()) {
            String key = team.getKey();
            result.add(summarizeTeam(team.getValue(),
                events.getOrDefault(key, List.of()),
                sessions.getOrDefault(key, List.of()),
                suggestions.getOrDefault(key, List.of()),
                coverage.getOrDefault(key, List.of())));
        }
        return result;
    }

    /**
     * Every team's records pooled into one result: medians, rates and coverage are computed over
     * all runs together (not averaged across teams). Only rows attributed to a team are counted,
     * the same rows the per-team results use.
     */
    public TeamValidationSummary overall(ValidationDataset data) {
        return summarizeTeam(OVERALL_ID,
            data.events().stream().filter(e -> hasTeam(e.getTeamId())).toList(),
            data.stageSessions().stream().filter(s -> hasTeam(s.getTeamId())).toList(),
            data.suggestions().stream().filter(s -> hasTeam(s.getTeamCode())).toList(),
            data.coverage().stream().filter(c -> hasTeam(c.getTeamId())).toList());
    }

    public static final String OVERALL_ID = "All teams";

    private static boolean hasTeam(String teamId) {
        return teamId != null && !teamId.isBlank();
    }

    TeamValidationSummary summarizeTeam(String teamId, List<ValidationEvent> events, List<StageSession> sessions,
            List<MappingSuggestion> suggestions, List<AnalysisCoverage> coverage) {
        ValidationProperties.Targets targets = properties.targets();
        List<MetricSummary> metrics = new ArrayList<>();
        for (MetricCode metric : MetricCode.values()) {
            metrics.add(switch (metric) {
                case M1, M2, M3, M8 -> timedEvents(metric, events, targets);
                case M4 -> stageSessions(null, sessions, targets);
                case M5 -> suggestions(suggestions, targets);
                case M6 -> analysisRuns(events, targets);
                case M7 -> coverage(coverage, targets);
            });
        }
        List<MetricSummary> perStage = new ArrayList<>();
        for (String stage : StageKeys.CODES.values()) {
            perStage.add(stageSessions(stage, sessions.stream().filter(s -> stage.equals(s.getStage())).toList(), targets));
        }
        return new TeamValidationSummary(teamId, metrics, perStage);
    }

    private MetricSummary timedEvents(MetricCode metric, List<ValidationEvent> all, ValidationProperties.Targets targets) {
        List<ValidationEvent> runs = all.stream().filter(e -> metric.name().equals(e.getMetricCode())).toList();
        List<Run> timed = runs.stream()
            .map(e -> new Run(e.getDurationMs(), ValidationEvent.SUCCESS.equals(e.getStatus()), e.getStatus()))
            .toList();
        return timed(metric, null, timed, targets.maxMsFor(metric), targets);
    }

    private MetricSummary analysisRuns(List<ValidationEvent> all, ValidationProperties.Targets targets) {
        Map<String, List<ValidationEvent>> byRun = new LinkedHashMap<>();
        for (ValidationEvent event : all) {
            if (!MetricCode.M6.name().equals(event.getMetricCode())) continue;
            if (ValidationOperation.ALIGNMENT.name().equals(event.getOperation())) continue; // decision D6
            String runKey = event.getAnalysisRunId() != null ? event.getAnalysisRunId() : "event-" + event.getId();
            byRun.computeIfAbsent(runKey, k -> new ArrayList<>()).add(event);
        }
        List<Run> runs = new ArrayList<>();
        for (List<ValidationEvent> steps : byRun.values()) {
            boolean hasRecommendations = steps.stream()
                .anyMatch(e -> ValidationOperation.RECOMMENDATIONS.name().equals(e.getOperation()));
            boolean anyUnsuccessful = steps.stream().anyMatch(e -> !ValidationEvent.SUCCESS.equals(e.getStatus()));
            if (!hasRecommendations && !anyUnsuccessful) continue; // run still in progress / abandoned
            Instant start = steps.stream().map(ValidationEvent::getStartedAt).min(Comparator.naturalOrder()).orElseThrow();
            Instant end = steps.stream().map(ValidationEvent::getEndedAt).max(Comparator.naturalOrder()).orElseThrow();
            String status = steps.stream().map(ValidationEvent::getStatus)
                .filter(s -> !ValidationEvent.SUCCESS.equals(s)).findFirst().orElse(ValidationEvent.SUCCESS);
            runs.add(new Run(Duration.between(start, end).toMillis(), ValidationEvent.SUCCESS.equals(status), status));
        }
        return timed(MetricCode.M6, null, runs, targets.m6MaxMs(), targets);
    }

    private MetricSummary stageSessions(String stage, List<StageSession> sessions, ValidationProperties.Targets targets) {
        List<StageSession> finalized = sessions.stream().filter(s -> s.getFinalizedAt() != null).toList();
        List<Run> runs = finalized.stream()
            .map(s -> new Run(s.getActiveMs(), true, ValidationEvent.SUCCESS)).toList();
        MetricSummary summary = timed(MetricCode.M4, stage, runs, targets.m4MaxMs(), targets);
        summary.setFailed(null);
        summary.setTimedOut(null);
        summary.setOpenSessions((int) sessions.stream().filter(s -> s.getFinalizedAt() == null).count());
        summary.setMedianWallClockMs(median(finalized.stream()
            .map(s -> s.getWallClockMs() == null ? 0L : s.getWallClockMs()).toList()));
        return summary;
    }

    private MetricSummary suggestions(List<MappingSuggestion> suggestions, ValidationProperties.Targets targets) {
        int accepted = 0, edited = 0, rejected = 0, pending = 0, withdrawn = 0, systemRejected = 0;
        for (MappingSuggestion s : suggestions) {
            switch (s.getStatus()) {
                case ACCEPTED -> accepted++;
                case EDITED -> edited++;
                case REJECTED -> {
                    if (s.isSystemRejected()) systemRejected++;
                    else rejected++;
                }
                case PENDING -> {
                    pending++;
                    if (s.isWithdrawn()) withdrawn++;
                }
            }
        }
        MetricSummary summary = base(MetricCode.M5, null, targets.m5MinRate(), "%");
        summary.setCount(suggestions.size());
        summary.setAccepted(accepted);
        summary.setEdited(edited);
        summary.setRejected(rejected);
        summary.setPending(pending);
        summary.setWithdrawn(withdrawn);
        summary.setSystemRejected(systemRejected);
        int decided = accepted + edited + rejected;
        Double rate = decided == 0 ? null : round1(100.0 * (accepted + edited) / decided);
        summary.setAcceptanceRate(rate);
        summary.setTargetMet(rate == null ? null : rate >= targets.m5MinRate());
        return summary;
    }

    private MetricSummary coverage(List<AnalysisCoverage> rows, ValidationProperties.Targets targets) {
        int counted = 0, both = 0, failures = 0;
        for (AnalysisCoverage row : rows) {
            if (row.countedGaps() == 0) continue; // a run with no gaps has nothing to explain (D7)
            counted += row.countedGaps();
            both += row.getGapsWithBoth();
            failures += row.getGenerationFailures();
        }
        MetricSummary summary = base(MetricCode.M7, null, targets.m7MinCoverage(), "%");
        summary.setCount(rows.size());
        summary.setGapsCounted(counted);
        summary.setGapsWithBoth(both);
        summary.setGenerationFailures(failures);
        Double pct = counted == 0 ? null : round1(100.0 * both / counted);
        summary.setCoveragePct(pct);
        summary.setTargetMet(pct == null ? null : pct >= targets.m7MinCoverage());
        return summary;
    }

    private record Run(long durationMs, boolean succeeded, String status) {}

    private MetricSummary timed(MetricCode metric, String stage, List<Run> runs, long maxMs,
            ValidationProperties.Targets targets) {
        MetricSummary summary = base(metric, stage, (double) maxMs, "ms");
        summary.setCount(runs.size());
        List<Long> durations = runs.stream().map(Run::durationMs).toList();
        summary.setMedianMs(median(durations));
        summary.setMaxMs(durations.stream().max(Long::compare).orElse(null));
        summary.setMinMs(durations.stream().min(Long::compare).orElse(null));
        summary.setFailed((int) runs.stream().filter(r -> ValidationEvent.FAILED.equals(r.status())).count());
        summary.setTimedOut((int) runs.stream().filter(r -> ValidationEvent.TIMEOUT.equals(r.status())).count());
        if (!runs.isEmpty()) {
            long within = runs.stream().filter(r -> r.succeeded() && r.durationMs() <= maxMs).count();
            double pct = round1(100.0 * within / runs.size());
            summary.setPctWithinTarget(pct);
            summary.setTargetMet(pct >= targets.requiredPctWithinTarget());
        }
        return summary;
    }

    private static MetricSummary base(MetricCode metric, String stage, double target, String unit) {
        MetricSummary summary = new MetricSummary();
        summary.setMetric(metric.name());
        summary.setLabel(metric.label());
        summary.setStage(stage);
        summary.setTarget(target);
        summary.setTargetUnit(unit);
        return summary;
    }

    /** Median; for an even count, the mean of the two middle values rounded to the nearest ms. */
    static Long median(List<Long> values) {
        if (values.isEmpty()) return null;
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(mid);
        return Math.round((sorted.get(mid - 1) + sorted.get(mid)) / 2.0);
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static void addTeam(Map<String, String> names, String teamId) {
        if (teamId == null || teamId.isBlank()) return;
        names.putIfAbsent(ValidationFilter.key(teamId), teamId.trim());
    }

    private static <T> Map<String, List<T>> groupByTeam(List<T> rows, Function<T, String> team) {
        return rows.stream().filter(row -> team.apply(row) != null)
            .collect(Collectors.groupingBy(row -> ValidationFilter.key(team.apply(row))));
    }

    // ── validation_team_summary refresh ─────────────────────────────────────

    /** Rewrites the team's all-time summary rows. Runs on the validation-metrics thread. */
    @Transactional
    public void refreshTeam(String teamId) {
        if (teamId == null || teamId.isBlank()) return;
        ValidationDataset data = loadAllTimeForTeam(teamId.trim());
        TeamValidationSummary summary = summarizeTeam(teamId.trim(), data.events(), data.stageSessions(),
            data.suggestions(), data.coverage());
        Instant now = Instant.now();
        List<TeamSummaryRow> rows = new ArrayList<>();
        summary.metrics().forEach(metric -> rows.add(toRow(summary.teamId(), metric, now)));
        summary.m4Stages().forEach(metric -> rows.add(toRow(summary.teamId(), metric, now)));
        summaryRowRepository.deleteByTeam(summary.teamId());
        summaryRowRepository.flush();
        summaryRowRepository.saveAll(rows);
    }

    private static TeamSummaryRow toRow(String teamId, MetricSummary metric, Instant now) {
        TeamSummaryRow row = new TeamSummaryRow();
        row.setKey(new TeamSummaryRow.Key(teamId, metric.getMetric(), metric.getStage() == null ? "" : metric.getStage()));
        row.setCount(metric.getCount());
        row.setMedianMs(metric.getMedianMs());
        row.setMaxMs(metric.getMaxMs());
        row.setMinMs(metric.getMinMs());
        row.setPctWithinTarget(metric.getPctWithinTarget());
        row.setTargetMet(metric.getTargetMet());
        row.setAccepted(metric.getAccepted());
        row.setEdited(metric.getEdited());
        row.setRejected(metric.getRejected());
        row.setPending(metric.getPending());
        row.setAcceptanceRate(metric.getAcceptanceRate());
        row.setCoveragePct(metric.getCoveragePct());
        row.setLastUpdated(now);
        return row;
    }
}
