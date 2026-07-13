import { useCallback, useEffect, useState } from 'react';
import { getSmartGoals, getGoalComponents } from '../api';
import { fetchClassRoster, fetchTeacherHistory } from '../../services/dashboardService';
import { extractSubmissionMeta } from '../../utils/dashboardUtils';
import { DOC_TYPES } from './useTraceability';

export function groupStatus(coveredCount, totalCells) {
  if (totalCells === 0 || coveredCount === 0) return 'critical';
  if (coveredCount === totalCells) return 'ready';
  return 'revision';
}

export const STATUS_META = {
  ready:    { label: 'Ready',          chip: 'status-chip--sent' },
  revision: { label: 'Needs Revision', chip: 'status-chip--pending' },
  critical: { label: 'Critical Gap',   chip: 'status-chip--critical' },
};

export function useGroupOverview(showToast) {
  const [goals, setGoals] = useState([]);
  const [groups, setGroups] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [goalList, roster, history] = await Promise.all([
        getSmartGoals(),
        fetchClassRoster().catch(() => []),
        fetchTeacherHistory().catch(() => []),
      ]);
      setGoals(goalList);

      const historyTeamMap = new Map();
      history.forEach((h) => {
        const meta = extractSubmissionMeta(h.fileName);
        if (meta.teamCode) historyTeamMap.set(h.id, meta.teamCode);
      });

      const teamSectionMap = new Map();
      const displayCodeByKey = new Map();
      const teamKeys = new Set();
      roster.forEach((s) => {
        if (!s.groupCode) return;
        const key = s.groupCode.toUpperCase();
        teamKeys.add(key);
        if (!displayCodeByKey.has(key)) displayCodeByKey.set(key, s.groupCode);
        if (s.section && !teamSectionMap.has(key)) teamSectionMap.set(key, s.section);
      });

      const perGoalComponents = await Promise.all(
        goalList.map((g) => getGoalComponents(g.id).catch(() => []))
      );

      const coverageByTeam = new Map();
      const lastTraceabilityByTeam = new Map();
      goalList.forEach((goal, gi) => {
        (perGoalComponents[gi] || []).forEach((c) => {
          const teamCode = c.sourceHistoryId != null ? historyTeamMap.get(c.sourceHistoryId) : null;
          if (!teamCode) return;
          const key = teamCode.toUpperCase();
          teamKeys.add(key);
          if (!displayCodeByKey.has(key)) displayCodeByKey.set(key, teamCode);
          if (!coverageByTeam.has(key)) coverageByTeam.set(key, new Set());
          coverageByTeam.get(key).add(`${goal.id}:${c.docType}`);

          if (c.createdAt) {
            const prev = lastTraceabilityByTeam.get(key);
            if (!prev || new Date(c.createdAt) > new Date(prev)) {
              lastTraceabilityByTeam.set(key, c.createdAt);
            }
          }
        });
      });

      const totalCells = goalList.length * DOC_TYPES.length;

      const groupList = [...teamKeys].sort((a, b) => a.localeCompare(b)).map((key) => {
        const teamCode = displayCodeByKey.get(key) || key;
        const covered = coverageByTeam.get(key) || new Set();
        const perGoal = goalList.map((goal, gi) => {
          const docTypeStatus = {};
          DOC_TYPES.forEach((dt) => { docTypeStatus[dt] = covered.has(`${goal.id}:${dt}`); });
          const coveredCount = DOC_TYPES.filter((dt) => docTypeStatus[dt]).length;
          return { goalId: goal.id, index: gi, description: goal.description, docTypeStatus, coveredCount };
        });
        const coveredCount = perGoal.reduce((sum, g) => sum + g.coveredCount, 0);
        const percent = totalCells > 0 ? Math.round((coveredCount / totalCells) * 100) : 0;

        return {
          teamCode,
          section: teamSectionMap.get(key) || '',
          percent,
          coveredCount,
          totalCells,
          status: groupStatus(coveredCount, totalCells),
          lastTraceability: lastTraceabilityByTeam.get(key) || null,
          perGoal,
        };
      });

      setGroups(groupList);
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    load();
  }, [load]);

  return { goals, groups, loading, reload: load };
}
