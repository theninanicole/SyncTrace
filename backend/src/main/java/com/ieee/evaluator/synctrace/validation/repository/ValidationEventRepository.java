package com.ieee.evaluator.synctrace.validation.repository;

import com.ieee.evaluator.synctrace.validation.model.ValidationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ValidationEventRepository extends JpaRepository<ValidationEvent, Long> {
    List<ValidationEvent> findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(
        Instant from, Instant to);

    List<ValidationEvent> findByTeamIdIgnoreCaseOrderByStartedAtAscIdAsc(String teamId);
}
