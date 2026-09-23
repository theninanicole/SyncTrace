package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.TraceabilityResultPublication;
import com.ieee.evaluator.synctrace.repository.TraceabilityResultPublicationRepository;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace/results")
public class TraceabilityResultPublicationController {

    private final TraceabilityResultPublicationRepository repository;
    private final SyncTraceAccessGuard accessGuard;

    public TraceabilityResultPublicationController(TraceabilityResultPublicationRepository repository,
            SyncTraceAccessGuard accessGuard) {
        this.repository = repository;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{teamCode}/publication")
    public ResponseEntity<?> getPublication(@PathVariable String teamCode) {
        try {
            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            String effectiveTeamCode = accessGuard.resolveEffectiveTeamCode(teamCode.trim());
            return repository.findByTeamCodeIgnoreCase(effectiveTeamCode)
                    .<ResponseEntity<?>>map((publication) -> ResponseEntity.ok(Map.of(
                            "published", true,
                            "teamCode", publication.getTeamCode(),
                            "publishedAt", publication.getPublishedAt()
                    )))
                    .orElseGet(() -> ResponseEntity.ok(Map.of(
                            "published", false,
                            "teamCode", effectiveTeamCode
                    )));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch traceability result publication."));
        }
    }

    @PostMapping("/{teamCode}/publication")
    public ResponseEntity<?> publishResults(@PathVariable String teamCode) {
        try {
            if (accessGuard.isStudent()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Students cannot publish traceability results."));
            }
            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            String normalizedTeamCode = teamCode.trim();
            TraceabilityResultPublication publication = repository
                    .findByTeamCodeIgnoreCase(normalizedTeamCode)
                    .orElseGet(TraceabilityResultPublication::new);

            publication.setTeamCode(normalizedTeamCode);
            publication.setPublishedAt(LocalDateTime.now());

            TraceabilityResultPublication saved = repository.save(publication);

            return ResponseEntity.ok(Map.of(
                    "message", "Traceability results sent to students.",
                    "published", true,
                    "teamCode", saved.getTeamCode(),
                    "publishedAt", saved.getPublishedAt()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send traceability results: " + e.getMessage()));
        }
    }

    @PostMapping("/publish-all")
    public ResponseEntity<?> publishAllResults(@RequestBody Map<String, List<String>> payload) {
        try {
            if (accessGuard.isStudent()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Students cannot publish traceability results."));
            }
            List<String> teamCodes = payload.getOrDefault("teamCodes", List.of());
            if (teamCodes.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCodes is required and cannot be empty"));
            }

            List<Map<String, Object>> results = new ArrayList<>();
            int successCount = 0;

            for (String rawTeamCode : teamCodes) {
                if (rawTeamCode == null || rawTeamCode.isBlank()) {
                    continue;
                }
                String normalizedTeamCode = rawTeamCode.trim();
                try {
                    TraceabilityResultPublication publication = repository
                            .findByTeamCodeIgnoreCase(normalizedTeamCode)
                            .orElseGet(TraceabilityResultPublication::new);

                    publication.setTeamCode(normalizedTeamCode);
                    publication.setPublishedAt(LocalDateTime.now());

                    TraceabilityResultPublication saved = repository.save(publication);
                    successCount++;

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("teamCode", saved.getTeamCode());
                    result.put("published", true);
                    result.put("publishedAt", saved.getPublishedAt());
                    results.add(result);
                } catch (Exception e) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("teamCode", normalizedTeamCode);
                    result.put("published", false);
                    result.put("error", e.getMessage());
                    results.add(result);
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Published results for " + successCount + " of " + results.size() + " team(s).");
            response.put("successCount", successCount);
            response.put("totalCount", results.size());
            response.put("results", results);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send traceability results to all teams: " + e.getMessage()));
        }
    }
}
