import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  getSmartGoals,
  getAllGoalComponents,
  getContinuityFindings,
  getContinuityAnalysisStatus,
  getDiagnosticRecommendations,
  getLatestMappingActivity,
} from '../api';
import { DOC_TYPES, groupGoalsIntoClusters } from '../constants';
import { buildAiIssues } from '../utils/gapIssues';
import { useMappingsChangedRefresh } from '../utils/mappingEvents';

export function isAnalysisStale(analysisStatus, mappingActivity) {
  const analyzedAt = analysisStatus?.lastAnalyzedAt;
  const changedAt = mappingActivity?.performedAt;
  return Boolean(analyzedAt && changedAt && new Date(changedAt) > new Date(analyzedAt));
}

export function useTraceabilityResultsData(teamCode, options = {}) {
  const { showToast, enabled = true } = options;
  const [goals, setGoals] = useState([]);
  const [componentsByGoal, setComponentsByGoal] = useState({});
  const [loading, setLoading] = useState(true);
  const [aiFindings, setAiFindings] = useState([]);
  const [aiRecommendations, setAiRecommendations] = useState([]);
  const [analysisStatus, setAnalysisStatus] = useState(null);
  const [mappingActivity, setMappingActivity] = useState(null);

  // silent: background refreshes keep the current matrix on screen instead of a loading state.
  const loadResults = useCallback(async ({ silent = false } = {}) => {
    if (!enabled) {
      setGoals([]);
      setComponentsByGoal({});
      setAiFindings([]);
      setAiRecommendations([]);
      setAnalysisStatus(null);
      setLoading(false);
      return;
    }

    if (!silent) setLoading(true);
    try {
      const [goalList, allGoalComponents, findingsData, analysisStatusData, recommendationsData, activity] = await Promise.all([
        getSmartGoals(teamCode || undefined),
        getAllGoalComponents(teamCode || undefined),
        teamCode ? getContinuityFindings(teamCode).catch(() => ({ findings: [] })) : { findings: [] },
        teamCode ? getContinuityAnalysisStatus(teamCode).catch(() => null) : null,
        teamCode ? getDiagnosticRecommendations(teamCode).catch(() => ({ recommendations: [] })) : { recommendations: [] },
        teamCode ? getLatestMappingActivity(teamCode).catch(() => null) : null,
      ]);
      setGoals(goalList);
      setComponentsByGoal(allGoalComponents);
      setAiFindings(findingsData.findings || []);
      setAnalysisStatus(analysisStatusData);
      setAiRecommendations(recommendationsData.recommendations || []);
      setMappingActivity(activity);
    } catch (err) {
      if (!silent) showToast?.(err.message, 'error');
    } finally {
      if (!silent) setLoading(false);
    }
  }, [enabled, showToast, teamCode]);

  useEffect(() => {
    loadResults();
  }, [loadResults]);

  const refreshSilently = useCallback(() => {
    if (enabled) loadResults({ silent: true });
  }, [enabled, loadResults]);
  useMappingsChangedRefresh(teamCode, refreshSilently, { pollMs: 30000 });

  // An analysis describes the mapping as it was when it ran; once the mapping has been saved
  // or changed since, it no longer applies and is hidden until the analysis is run again.
  const analysisCurrent = !isAnalysisStale(analysisStatus, mappingActivity);
  const currentFindings = useMemo(() => (analysisCurrent ? aiFindings : []), [analysisCurrent, aiFindings]);
  const currentRecommendations = useMemo(
    () => (analysisCurrent ? aiRecommendations : []),
    [analysisCurrent, aiRecommendations],
  );

  const rows = useMemo(() => {
    return groupGoalsIntoClusters(goals).map((cluster, clusterIndex) => {
      const memberGoalIds = [cluster.primary.id, ...cluster.children.map((child) => child.id)];
      const mappedById = new Map();
      memberGoalIds.forEach((goalId) => {
        const mapped = componentsByGoal[goalId] || componentsByGoal[String(goalId)] || [];
        mapped.forEach((component) => {
          mappedById.set(component.id ?? `${component.docType}:${component.name}`, component);
        });
      });
      const cells = {};
      DOC_TYPES.forEach((dt) => {
        cells[dt] = [...mappedById.values()].filter((component) => component.docType === dt);
      });
      const validationIssues = [];
      const memberGoalIdSet = new Set(memberGoalIds);
      currentFindings
        .filter((finding) => memberGoalIdSet.has(finding.goalId))
        .forEach((finding) => validationIssues.push(finding.description));
      const coveredTypes = DOC_TYPES.filter((dt) => cells[dt].length > 0).length;
      return {
        goalId: cluster.id,
        memberGoalIds,
        // Numbered by position within the team (G1, G2, …), matching the group view.
        code: `G${clusterIndex + 1}`,
        description: cluster.primary.description,
        specificDescriptions: cluster.children.map((child) => child.description),
        teamCode: cluster.primary.teamCode || '',
        cells,
        coveredTypes,
        aligned: validationIssues.length === 0,
        validationIssues,
        createdAt: cluster.primary.createdAt,
      };
    });
  }, [currentFindings, componentsByGoal, goals]);

  const metrics = useMemo(() => {
    let missingCells = 0;
    let unmappedGoals = 0;
    let partialGoals = 0;
    let fullyCoveredGoals = 0;

    rows.forEach((r) => {
      DOC_TYPES.forEach((dt) => {
        if (!r.cells[dt] || r.cells[dt].length === 0) missingCells++;
      });

      if (r.coveredTypes === 0) unmappedGoals++;
      else if (r.coveredTypes < DOC_TYPES.length) partialGoals++;
      else fullyCoveredGoals++;
    });

    const alignmentPercent = rows.length === 0
      ? 0
      : Math.round((1 - (missingCells / (rows.length * DOC_TYPES.length))) * 100);

    return {
      alignmentPercent,
      unmappedGoals,
      partialGoals,
      missingCells,
      fullyCoveredGoals,
    };
  }, [rows]);

  const goalCodeById = useMemo(() => {
    const codeById = new Map();
    rows.forEach((row) => {
      row.memberGoalIds.forEach((goalId) => codeById.set(goalId, row.code));
    });
    return codeById;
  }, [rows]);

  const aiIssues = useMemo(
    () => buildAiIssues(currentFindings, currentRecommendations, goalCodeById),
    [currentFindings, currentRecommendations, goalCodeById],
  );

  return {
    goals,
    componentsByGoal,
    loading,
    rows,
    metrics,
    aiFindings: currentFindings,
    aiRecommendations: currentRecommendations,
    analysisStatus: analysisCurrent ? analysisStatus : null,
    aiIssues,
    setAiFindings,
    setAiRecommendations,
    refresh: () => loadResults(),
  };
}
