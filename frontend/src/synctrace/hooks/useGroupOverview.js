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

      // historyId -> resolved team code, keyed off the filename convention already
      // used elsewhere in the app (extractSubmissionMeta).
      const historyTeamMap = new Map();
      history.forEach((h) => {
        const meta = extractSubmissionMeta(h.fileName);
        if (meta.teamCode) historyTeamMap.set(h.id, meta.teamCode);
      });

      const teamSectionMap = new Map();
      const teamCodes = new Set();
      roster.forEach((s) => {
        if (!s.groupCode) return;
        teamCodes.add(s.groupCode);
        if (s.section && !teamSectionMap.has(s.groupCode)) teamSectionMap.set(s.groupCode, s.section);
      });

      const perGoalComponents = await Promise.all(
        goalList.map((g) => getGoalComponents(g.id).catch(() => []))
      );

      // teamCode -> Set of "goalId:docType" cells covered by that team's own components,
      // and the most recent time a component covering one of those cells was mapped.
      const coverageByTeam = new Map();
      const lastTraceabilityByTeam = new Map();
      goalList.forEach((goal, gi) => {
        (perGoalComponents[gi] || []).forEach((c) => {
          const teamCode = c.sourceHistoryId != null ? historyTeamMap.get(c.sourceHistoryId) : null;
          if (!teamCode) return;
          teamCodes.add(teamCode);
          if (!coverageByTeam.has(teamCode)) coverageByTeam.set(teamCode, new Set());
          coverageByTeam.get(teamCode).add(`${goal.id}:${c.docType}`);

          if (c.createdAt) {
            const prev = lastTraceabilityByTeam.get(teamCode);
            if (!prev || new Date(c.createdAt) > new Date(prev)) {
              lastTraceabilityByTeam.set(teamCode, c.createdAt);
            }
          }
        });
      });

      const totalCells = goalList.length * DOC_TYPES.length;

      const groupList = [...teamCodes].sort((a, b) => a.localeCompare(b)).map((teamCode) => {
        const covered = coverageByTeam.get(teamCode) || new Set();
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
          section: teamSectionMap.get(teamCode) || '',
          percent,
          coveredCount,
          totalCells,
          status: groupStatus(coveredCount, totalCells),
          lastTraceability: lastTraceabilityByTeam.get(teamCode) || null,
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
