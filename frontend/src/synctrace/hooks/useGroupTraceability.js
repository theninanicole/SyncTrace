import { useCallback, useEffect, useState } from 'react';
import { getSmartGoals, getAllGoalComponents } from '../api';
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
};

export function useGroupTraceability(teamCode, showToast) {
  const [state, setGroupState] = useState(EMPTY_STATE);

  const load = useCallback(async () => {
    if (!teamCode) return;
    setGroupState((s) => ({ ...s, loading: true }));
    try {
      const [goalList, roster, history, componentsByGoal] = await Promise.all([
        getSmartGoals(),
        fetchClassRoster().catch(() => []),
        fetchTeacherHistory().catch(() => []),
        getAllGoalComponents().catch(() => ({})),
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
          const componentTeam = c.sourceHistoryId != null ? historyTeamMap.get(c.sourceHistoryId) : null;
          const goalTeam = (goal.teamCode || '').toUpperCase();
          if (componentTeam && componentTeam.toUpperCase() !== teamCode.toUpperCase()) return;
          if (!componentTeam && goalTeam && goalTeam !== teamCode.toUpperCase()) return;
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
          code: `G${gi + 1}`,
          description: goal.description,
          goalKind: goal.goalKind || 'SPECIFIC',
          parentGoalId: goal.parentGoalId || null,
          parentCode: parentIdx != null ? `G${parentIdx + 1}` : null,
          teamCode: goal.teamCode || '',
          cells,
          aligned: coveredTypes === DOC_TYPES.length,
          nested: goal.goalKind !== 'GENERAL' && Boolean(goal.parentGoalId),
          createdAt: goal.createdAt,
        };
      });

      const totalCells = orderedGoals.length * DOC_TYPES.length;
      const percent = totalCells > 0 ? Math.round((coveredCount / totalCells) * 100) : 0;

      setGroupState((s) => ({
        ...s,
        section,
        rows,
        percent,
        status: groupStatus(coveredCount, totalCells),
        coveredCount,
        totalCells,
        lastTraceability,
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
