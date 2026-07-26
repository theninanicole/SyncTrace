package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TraceComponentRepository extends JpaRepository<TraceComponent, Long> {
    List<TraceComponent> findByDocTypeOrderByCreatedAtDesc(DocType docType);
    Optional<TraceComponent> findByDocTypeAndNameIgnoreCase(DocType docType, String name);
    List<TraceComponent> findAllByDocTypeAndCodeNameIgnoreCase(DocType docType, String codeName);
    List<TraceComponent> findByDocTypeAndNameContainingIgnoreCase(DocType docType, String search);
    List<TraceComponent> findByNameContainingIgnoreCase(String search);
}
