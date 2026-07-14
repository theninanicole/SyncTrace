package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.Component;
import com.ieee.evaluator.synctrace.model.ComponentSummaryDTO;
import com.ieee.evaluator.synctrace.model.DocType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ComponentRepository extends JpaRepository<Component, Long> {

    List<Component> findAllByOrderByIdDesc();

    List<Component> findByDocTypeOrderByIdDesc(DocType docType);

    List<Component> findByNameContainingIgnoreCaseOrderByIdDesc(String name);

    List<Component> findByDocTypeAndNameContainingIgnoreCaseOrderByIdDesc(DocType docType, String name);

    Optional<Component> findByDocTypeAndNameIgnoreCase(DocType docType, String name);

    // Projections below intentionally omit `content` and `image_data` — those TEXT
    // columns can be multi-MB per row, and list/summary views never render them.

    interface DocTypeOnly {
        Long getId();
        DocType getDocType();
    }

    @Query("select c.id as id, c.docType as docType from Component c where c.id in :ids")
    List<DocTypeOnly> findDocTypesByIdIn(@Param("ids") Collection<Long> ids);

    @Query("select new com.ieee.evaluator.synctrace.model.ComponentSummaryDTO(" +
           "c.id, c.docType, c.name, c.sourceHistoryId, c.createdAt) " +
           "from Component c where c.id in :ids")
    List<ComponentSummaryDTO> findSummariesByIdIn(@Param("ids") Collection<Long> ids);
}
