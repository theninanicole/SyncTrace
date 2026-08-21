import { useCallback, useEffect, useMemo, useState } from 'react';
import { getSmartGoals, getAllGoalComponents, getContinuityFindings, getDiagnosticRecommendations } from '../api';
import { DOC_TYPES } from '../constants';
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
    return goals.map((goal, gi) => {
      const mapped = componentsByGoal[goal.id] || componentsByGoal[String(goal.id)] || [];
      const cells = {};
      DOC_TYPES.forEach((dt) => { cells[dt] = mapped.filter((c) => c.docType === dt); });
      const coveredTypes = DOC_TYPES.filter((dt) => cells[dt].length > 0).length;
      return {
        goalId: goal.id,
        code: `G${gi + 1}`,
        description: goal.description,
        teamCode: goal.teamCode || '',
        cells,
        coveredTypes,
        aligned: coveredTypes === DOC_TYPES.length,
        createdAt: goal.createdAt,
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

  const goalCodeById = useMemo(
    () => new Map(rows.map((r) => [r.goalId, r.code])),
    [rows],
  );

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
