package com.ieee.evaluator.synctrace.validation.controller;

import com.ieee.evaluator.model.TeamRepository;
import com.ieee.evaluator.service.SubmissionSyncService;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import com.ieee.evaluator.synctrace.validation.config.ValidationProperties;
import com.ieee.evaluator.synctrace.validation.model.ValidationSession;
import com.ieee.evaluator.synctrace.validation.repository.ValidationSessionRepository;
import com.ieee.evaluator.synctrace.validation.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Validation Metrics. Every endpoint except the stage heartbeat is teacher-only, enforced here
 * on the server ({@link SyncTraceAccessGuard#requireTeacher()}), not just by hiding the page.
 */
@Slf4j
@RestController
@RequestMapping("/api/synctrace/validation")
public class ValidationMetricsController {

    private static final MediaType XLSX =
        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final SyncTraceAccessGuard accessGuard;
    private final ValidationSummaryService summaryService;
    private final ValidationExportService exportService;
    private final ValidationSessionRepository sessionRepository;
    private final ValidationRecorder recorder;
    private final ValidationProperties properties;
    private final SubmissionSyncService submissionSyncService;

    public ValidationMetricsController(SyncTraceAccessGuard accessGuard, ValidationSummaryService summaryService,
            ValidationExportService exportService, ValidationSessionRepository sessionRepository,
            ValidationRecorder recorder, ValidationProperties properties,
            SubmissionSyncService submissionSyncService) {
        this.accessGuard = accessGuard;
        this.summaryService = summaryService;
        this.exportService = exportService;
        this.sessionRepository = sessionRepository;
        this.recorder = recorder;
        this.properties = properties;
        this.submissionSyncService = submissionSyncService;
    }

    @ExceptionHandler(SyncTraceAccessGuard.AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(SyncTraceAccessGuard.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    // ── Read ────────────────────────────────────────────────────────────────

    @GetMapping("/targets")
    public ResponseEntity<?> targets() {
        accessGuard.requireTeacher();
        return ResponseEntity.ok(Map.of(
            "targets", properties.targets(),
            "heartbeatIntervalMs", properties.heartbeatIntervalMs(),
            "idleGapMs", properties.idleGapMs()));
    }

    @GetMapping("/teams")
    public ResponseEntity<?> teams() {
        accessGuard.requireTeacher();
        Map<String, String> teams = new TreeMap<>();
        try {
            for (TeamRepository team : submissionSyncService.getTeamRepositories()) {
                if (team.teamCode() != null && !team.teamCode().isBlank()) {
                    teams.putIfAbsent(ValidationFilter.key(team.teamCode()), team.teamCode().trim());
                }
            }
        } catch (Exception e) {
            log.warn("Class roster unavailable for the validation team list: {}", e.getMessage());
        }
        for (TeamValidationSummary summary : summaryService.summarize(ValidationFilter.allTime(List.of()))) {
            teams.putIfAbsent(ValidationFilter.key(summary.teamId()), summary.teamId());
        }
        return ResponseEntity.ok(new ArrayList<>(teams.values()));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(
            @RequestParam(required = false) String teamCodes,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        accessGuard.requireTeacher();
        ValidationFilter filter = resolveFilter(teamCodes, sessionId, from, to);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filter", Map.of(
            "teamCodes", filter.teamIds(),
            "from", filter.from().equals(ValidationFilter.BEGINNING) ? "" : filter.from().toString(),
            "to", filter.to().toString(),
            "label", filter.label()));
        body.put("targets", properties.targets());
        ValidationDataset data = summaryService.load(filter);
        body.put("teams", summaryService.summarize(filter, data));
        body.put("overall", summaryService.overall(data));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(
            @RequestParam(defaultValue = "zip") String format,
            @RequestParam(required = false) String teamCodes,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws Exception {
        accessGuard.requireTeacher();
        String normalized = format.toLowerCase(Locale.ROOT);
        if (!normalized.equals("zip") && !normalized.equals("xlsx")) {
            throw new IllegalArgumentException("format must be zip or xlsx");
        }
        ValidationFilter filter = resolveFilter(teamCodes, sessionId, from, to);
        byte[] data = normalized.equals("zip") ? exportService.exportZip(filter) : exportService.exportExcel(filter);

        String filename = "validation-metrics-" + slug(filter.label()) + "-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "." + normalized;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(normalized.equals("zip") ? MediaType.parseMediaType("application/zip") : XLSX);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setAccessControlExposeHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION));
        return ResponseEntity.ok().headers(headers).body(data);
    }

    // ── Validation sessions (named windows for filtering) ───────────────────

    @GetMapping("/sessions")
    public ResponseEntity<?> sessions() {
        accessGuard.requireTeacher();
        return ResponseEntity.ok(sessionRepository.findAllByOrderByStartsAtDescIdDesc());
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(@RequestBody Map<String, Object> payload) {
        accessGuard.requireTeacher();
        String name = text(payload.get("name"));
        if (name == null) throw new IllegalArgumentException("name is required");
        Instant startsAt = parseInstant(text(payload.get("startsAt")), "startsAt");
        Instant endsAt = parseInstant(text(payload.get("endsAt")), "endsAt");
        if (startsAt == null || endsAt == null) throw new IllegalArgumentException("startsAt and endsAt are required");
        if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("The session must end after it starts.");

        ValidationSession session = new ValidationSession();
        session.setName(name.length() > 200 ? name.substring(0, 200) : name);
        String date = text(payload.get("sessionDate"));
        try {
            session.setSessionDate(date != null ? LocalDate.parse(date) : startsAt.atZone(ZoneId.systemDefault()).toLocalDate());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("sessionDate must be YYYY-MM-DD");
        }
        session.setStartsAt(startsAt);
        session.setEndsAt(endsAt);
        session.setTeamIds(String.join(",", teamList(payload.get("teamIds"))));
        session.setCreatedBy(accessGuard.currentUserId());
        session.setCreatedAt(Instant.now());
        return ResponseEntity.ok(sessionRepository.save(session));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable Long id) {
        accessGuard.requireTeacher();
        if (!sessionRepository.existsById(id)) throw new NoSuchElementException("Validation session not found.");
        sessionRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── M4 heartbeat (students and teachers; scoped to the caller's team) ────

    @PostMapping("/stage-heartbeat")
    public ResponseEntity<?> stageHeartbeat(@RequestBody Map<String, Object> payload) {
        String teamCode = accessGuard.resolveEffectiveTeamCode(text(payload.get("teamCode")));
        if (teamCode == null || teamCode.isBlank()) throw new IllegalArgumentException("teamCode is required");
        String stageCode = StageKeys.toCode(text(payload.get("stage")));
        if (stageCode == null) throw new IllegalArgumentException("Unknown mapping stage.");
        boolean visible = !"hidden".equalsIgnoreCase(text(payload.get("state")));
        recorder.heartbeat(teamCode, stageCode, visible);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    ValidationFilter resolveFilter(String teamCodes, Long sessionId, String from, String to) {
        Set<String> teams = new LinkedHashSet<>(teamList(teamCodes));
        if (sessionId != null) {
            ValidationSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Validation session not found."));
            if (teams.isEmpty()) teams.addAll(session.teamIdList());
            return new ValidationFilter(teams, session.getStartsAt(), session.getEndsAt(), session.getName());
        }
        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");
        if (fromInstant != null && toInstant != null && !toInstant.isAfter(fromInstant)) {
            throw new IllegalArgumentException("'to' must be after 'from'.");
        }
        String label = fromInstant == null && toInstant == null ? "all-time"
            : (fromInstant == null ? "start" : fromInstant.atZone(ZoneId.systemDefault()).toLocalDate().toString())
              + "-to-" + (toInstant == null ? "now" : toInstant.atZone(ZoneId.systemDefault()).toLocalDate().toString());
        return new ValidationFilter(teams, fromInstant, toInstant, label);
    }

    /** Accepts an ISO instant (2026-11-25T01:00:00Z), an offset date-time, or a plain date. */
    static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // try the other formats
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // try a plain date
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO date or date-time");
        }
    }

    private static List<String> teamList(Object raw) {
        List<String> teams = new ArrayList<>();
        if (raw instanceof Collection<?> values) {
            values.forEach(value -> addTeams(teams, value == null ? null : String.valueOf(value)));
        } else if (raw != null) {
            addTeams(teams, String.valueOf(raw));
        }
        return teams;
    }

    private static void addTeams(List<String> teams, String csv) {
        if (csv == null) return;
        for (String part : csv.split(",")) {
            String team = part.trim();
            if (!team.isEmpty() && teams.stream().noneMatch(t -> t.equalsIgnoreCase(team))) teams.add(team);
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String slug(String label) {
        String slug = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) return "export";
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }
}
