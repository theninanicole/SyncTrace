import { useCallback, useEffect, useState } from 'react';
import { getSmartGoals, getAllGoalComponents, getContinuitySummary } from '../api';
import { fetchClassRoster, fetchTeacherHistory } from '../../services/dashboardService';
import { extractSubmissionMeta } from '../../utils/dashboardUtils';
import { DOC_TYPES } from './useTraceability';
import { orderGoalsHierarchically } from '../constants';
import { groupStatus } from './useGroupOverview';

const EMPTY_STATE = {
  loading: true,
  section: '',
  rows: [],
  percent: 0,
  status: 'critical',
  coveredCount: 0,
  totalCells: 0,
  lastTraceability: null,
  readinessScore: 0,
  readinessStatus: null,
  findingCount: 0,
};

function resolveComponentTeamCode(component, historyTeamMap) {
  if (component.sourceHistoryId != null) {
    return historyTeamMap.get(component.sourceHistoryId) || null;
  }

  if (component.docType === 'IMPLEMENTATION' && typeof component.name === 'string') {
    const separatorIndex = component.name.indexOf(' - ');
    if (separatorIndex > 0) {
      return component.name.slice(0, separatorIndex).trim() || null;
    }
  }

  return null;
}

function mapBackendStatus(status) {
  switch (status) {
    case 'READY':
      return 'ready';
    case 'ON_TRACK':
      return 'revision';
    case 'AT_RISK':
    case 'BLOCKED':
      return 'critical';
    default:
      return 'critical';
  }
}

export function useGroupTraceability(teamCode, showToast) {
  const [state, setGroupState] = useState(EMPTY_STATE);

  const load = useCallback(async () => {
    if (!teamCode) return;
    setGroupState((s) => ({ ...s, loading: true }));
    try {
      const [goalList, roster, history, componentsByGoal, summary] = await Promise.all([
        getSmartGoals(),
        fetchClassRoster().catch(() => []),
        fetchTeacherHistory().catch(() => []),
        getAllGoalComponents().catch(() => ({})),
        getContinuitySummary(teamCode).catch(() => null),
      ]);

      const historyTeamMap = new Map();
      history.forEach((h) => {
        const meta = extractSubmissionMeta(h.fileName);
        if (meta.teamCode) historyTeamMap.set(h.id, meta.teamCode);
      });

      const section = roster.find((s) => s.groupCode?.toUpperCase() === teamCode.toUpperCase())?.section || '';

      const orderedGoals = orderGoalsHierarchically(goalList);
      const indexById = new Map(orderedGoals.map((g, i) => [g.id, i]));
      const perGoalComponents = orderedGoals.map((g) =>
        componentsByGoal[g.id] || componentsByGoal[String(g.id)] || []
      );

      let lastTraceability = null;
      let coveredCount = 0;

      const rows = orderedGoals.map((goal, gi) => {
        const cells = {};
        DOC_TYPES.forEach((dt) => { cells[dt] = []; });

        (perGoalComponents[gi] || []).forEach((c) => {
          const componentTeam = resolveComponentTeamCode(c, historyTeamMap);
          if (componentTeam?.toUpperCase() !== teamCode.toUpperCase()) return;
          cells[c.docType].push(c);
          if (c.createdAt && (!lastTraceability || new Date(c.createdAt) > new Date(lastTraceability))) {
            lastTraceability = c.createdAt;
          }
        });

        const coveredTypes = DOC_TYPES.filter((dt) => cells[dt].length > 0).length;
        coveredCount += coveredTypes;
        const parentIdx = goal.parentGoalId != null ? indexById.get(goal.parentGoalId) : null;

        return {
          goalId: goal.id,
          code: `G-${String(gi + 1).padStart(2, '0')}`,
          description: goal.description,
          goalKind: goal.goalKind || 'SPECIFIC',
          parentGoalId: goal.parentGoalId || null,
          parentCode: parentIdx != null ? `G-${String(parentIdx + 1).padStart(2, '0')}` : null,
          teamCode: goal.teamCode || '',
          cells,
          aligned: coveredTypes === DOC_TYPES.length,
          nested: goal.goalKind !== 'GENERAL' && Boolean(goal.parentGoalId),
          createdAt: goal.createdAt,
        };
      });

      const summaryCoveredCount = summary
        ? summary.goalSummaries.reduce((sum, goalSummary) => sum + goalSummary.coveredDocTypes.length, 0)
        : coveredCount;
      const totalCells = summary ? summary.totalGoals * DOC_TYPES.length : goalList.length * DOC_TYPES.length;
      const percent = totalCells > 0 ? Math.round((summaryCoveredCount / totalCells) * 100) : 0;

      setGroupState((s) => ({
        ...s,
        section,
        rows,
        percent,
        status: summary ? mapBackendStatus(summary.status) : groupStatus(coveredCount, totalCells),
        coveredCount: summaryCoveredCount,
        totalCells,
        lastTraceability,
        readinessScore: summary?.readinessScore ?? percent,
        readinessStatus: summary?.status ?? null,
        findingCount: summary?.totalFindings ?? 0,
      }));
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setGroupState((s) => ({ ...s, loading: false }));
    }
  }, [teamCode, showToast]);

  useEffect(() => {
    load();
  }, [load]);

  return { ...state, reload: load };
}
