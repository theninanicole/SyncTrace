import { useCallback, useEffect, useMemo, useState } from 'react';
import { getSmartGoals, getAllGoalComponents, getContinuityFindings, getDiagnosticRecommendations } from '../api';
import { DOC_TYPES, groupGoalsIntoClusters } from '../constants';
import { buildAiIssues } from '../utils/gapIssues';

export function useTraceabilityResultsData(teamCode, options = {}) {
  const { showToast, enabled = true } = options;
  const [goals, setGoals] = useState([]);
  const [componentsByGoal, setComponentsByGoal] = useState({});
  const [loading, setLoading] = useState(true);
  const [aiFindings, setAiFindings] = useState([]);
  const [aiRecommendations, setAiRecommendations] = useState([]);

  const loadResults = useCallback(async () => {
    if (!enabled) {
      setGoals([]);
      setComponentsByGoal({});
      setAiFindings([]);
      setAiRecommendations([]);
      setLoading(false);
      return;
    }

    setLoading(true);
    try {
      const [goalList, allGoalComponents, findingsData, recommendationsData] = await Promise.all([
        getSmartGoals(teamCode || undefined),
        getAllGoalComponents(),
        teamCode ? getContinuityFindings(teamCode).catch(() => ({ findings: [] })) : { findings: [] },
        teamCode ? getDiagnosticRecommendations(teamCode).catch(() => ({ recommendations: [] })) : { recommendations: [] },
      ]);
      setGoals(goalList);
      setComponentsByGoal(allGoalComponents);
      setAiFindings(findingsData.findings || []);
      setAiRecommendations(recommendationsData.recommendations || []);
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoading(false);
    }
  }, [enabled, showToast, teamCode]);

  useEffect(() => {
    loadResults();
  }, [loadResults]);

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
      const coveredTypes = DOC_TYPES.filter((dt) => cells[dt].length > 0).length;
      return {
        goalId: cluster.id,
        memberGoalIds,
        code: `G${clusterIndex + 1}`,
        description: cluster.primary.description,
        specificDescriptions: cluster.children.map((child) => child.description),
        teamCode: cluster.primary.teamCode || '',
        cells,
        coveredTypes,
        aligned: coveredTypes === DOC_TYPES.length,
        createdAt: cluster.primary.createdAt,
      };
    });
  }, [componentsByGoal, goals]);

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
    () => buildAiIssues(aiFindings, aiRecommendations, goalCodeById),
    [aiFindings, aiRecommendations, goalCodeById],
  );

  return {
    goals,
    componentsByGoal,
    loading,
    rows,
    metrics,
    aiFindings,
    aiRecommendations,
    aiIssues,
    setAiFindings,
    setAiRecommendations,
    refresh: loadResults,
  };
}
