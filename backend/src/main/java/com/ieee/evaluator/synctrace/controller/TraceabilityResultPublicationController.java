package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.TraceabilityResultPublication;
import com.ieee.evaluator.synctrace.repository.TraceabilityResultPublicationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace/results")
public class TraceabilityResultPublicationController {

    private final TraceabilityResultPublicationRepository repository;

    public TraceabilityResultPublicationController(TraceabilityResultPublicationRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{teamCode}/publication")
    public ResponseEntity<?> getPublication(@PathVariable String teamCode) {
        try {
            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            return repository.findByTeamCodeIgnoreCase(teamCode.trim())
                    .<ResponseEntity<?>>map((publication) -> ResponseEntity.ok(Map.of(
                            "published", true,
                            "teamCode", publication.getTeamCode(),
                            "publishedAt", publication.getPublishedAt()
                    )))
                    .orElseGet(() -> ResponseEntity.ok(Map.of(
                            "published", false,
                            "teamCode", teamCode.trim()
                    )));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch traceability result publication."));
        }
    }

    @PostMapping("/{teamCode}/publication")
    public ResponseEntity<?> publishResults(@PathVariable String teamCode) {
        try {
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
}
