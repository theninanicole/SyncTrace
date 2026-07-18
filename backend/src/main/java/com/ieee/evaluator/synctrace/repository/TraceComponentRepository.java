package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.TraceComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TraceComponentRepository extends JpaRepository<TraceComponent, Long> {

    Optional<TraceComponent> findFirstByDocTypeAndNameIgnoreCase(String docType, String name);
}
