package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.Component;
import com.ieee.evaluator.synctrace.model.DocType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComponentRepository extends JpaRepository<Component, Long> {

    List<Component> findAllByOrderByIdDesc();

    List<Component> findByDocTypeOrderByIdDesc(DocType docType);

    List<Component> findByNameContainingIgnoreCaseOrderByIdDesc(String name);

    List<Component> findByDocTypeAndNameContainingIgnoreCaseOrderByIdDesc(DocType docType, String name);

    Optional<Component> findByDocTypeAndNameIgnoreCase(DocType docType, String name);
}
