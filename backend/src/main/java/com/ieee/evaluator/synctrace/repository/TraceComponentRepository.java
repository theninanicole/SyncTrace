package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TraceComponentRepository extends JpaRepository<TraceComponent, Long> {

    // Selects columns rather than entities so list views never read imageData.
    String SUMMARY_SELECT =
        "SELECT new com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO(" +
        "c.id, c.docType, c.artifactKind, c.name, c.content, c.codeName, c.aiExtracted, c.sourceHistoryId, " +
        "c.sourceType, c.sourceRef, c.sourceUrl, c.sourceRemoved, c.sourceCapturedAt, c.createdAt) " +
        "FROM TraceComponent c ";

    // An empty search matches everything.
    String SUMMARY_FILTER =
        "(:docType IS NULL OR c.docType = :docType) " +
        "AND (:search = '' OR LOCATE(LOWER(:search), LOWER(c.name)) > 0) ";

    // Narrows to a team's candidates; TeamComponentResolverService applies the exact ownership rule.
    String TEAM_CANDIDATES =
        "(c.sourceHistoryId IN :historyIds " +
        "OR (c.sourceHistoryId IS NULL " +
        "AND c.docType = com.ieee.evaluator.synctrace.model.TraceComponent.DocType.IMPLEMENTATION " +
        "AND LOCATE(LOWER(:teamCode), LOWER(c.name)) > 0) " +
        "OR LOWER(c.sourceRef) = LOWER(CONCAT('team:', :teamCode))) ";

    /**
     * Library order: extracted components follow the Diagram Analysis of the evaluation they
     * came from; everything else (manual, GitHub, extractions that predate displayOrder)
     * keeps the order it was added in.
     */
    String LIBRARY_ORDER = "ORDER BY c.sourceHistoryId ASC NULLS LAST, c.displayOrder ASC NULLS LAST, c.id ASC";

    @Query(SUMMARY_SELECT + "WHERE " + SUMMARY_FILTER + LIBRARY_ORDER)
    List<TraceComponentSummaryDTO> findSummaries(
        @Param("docType") DocType docType, @Param("search") String search);

    @Query(SUMMARY_SELECT + "WHERE " + TEAM_CANDIDATES + "AND " + SUMMARY_FILTER + LIBRARY_ORDER)
    List<TraceComponentSummaryDTO> findTeamCandidateSummaries(
        @Param("historyIds") Collection<Long> historyIds,
        @Param("teamCode") String teamCode,
        @Param("docType") DocType docType,
        @Param("search") String search);

    @Query("SELECT c FROM TraceComponent c WHERE " + TEAM_CANDIDATES)
    List<TraceComponent> findTeamCandidates(
        @Param("historyIds") Collection<Long> historyIds, @Param("teamCode") String teamCode);

    Optional<TraceComponent> findByDocTypeAndNameIgnoreCase(DocType docType, String name);
    Optional<TraceComponent> findFirstByDocTypeAndNameIgnoreCaseAndSourceRefIgnoreCase(
        DocType docType, String name, String sourceRef);
    List<TraceComponent> findAllByDocTypeAndCodeNameIgnoreCase(DocType docType, String codeName);
    List<TraceComponent> findAllBySourceHistoryId(Long sourceHistoryId);

    List<TraceComponent> findAllBySourceTypeAndSourceRefStartingWithIgnoreCase(String sourceType, String sourceRefPrefix);
}
