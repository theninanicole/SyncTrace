package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.TraceabilityResultPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TraceabilityResultPublicationRepository extends JpaRepository<TraceabilityResultPublication, Long> {

    Optional<TraceabilityResultPublication> findByTeamCodeIgnoreCase(String teamCode);

    boolean existsByTeamCodeIgnoreCase(String teamCode);
}
