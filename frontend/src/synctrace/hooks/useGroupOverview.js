import { useCallback, useEffect, useState } from 'react';
import { getSmartGoals, getAllGoalComponents } from '../api';
import { fetchClassRoster } from '../../services/dashboardService';
import { DOC_TYPES, clusterCategoryStatus, groupGoalsIntoClusters } from '../constants';

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
      const [goalList, roster, componentsByGoal] = await Promise.all([
        getSmartGoals(),
        fetchClassRoster().catch(() => []),
        getAllGoalComponents().catch(() => ({})),
      ]);
      setGoals(goalList);

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

      // Goals are team-scoped, so a team's coverage is measured against its own goal
      // clusters (the rows of its matrix) — not against every team's goals combined, and
      // without guessing ownership from the teacher's evaluation history.
      const goalsByTeam = new Map();
      goalList.forEach((goal) => {
        if (!goal.teamCode) return;
        const key = goal.teamCode.toUpperCase();
        teamKeys.add(key);
        if (!displayCodeByKey.has(key)) displayCodeByKey.set(key, goal.teamCode);
        if (!goalsByTeam.has(key)) goalsByTeam.set(key, []);
        goalsByTeam.get(key).push(goal);
      });

      const groupList = [...teamKeys].sort((a, b) => a.localeCompare(b)).map((key) => {
        const teamCode = displayCodeByKey.get(key) || key;
        const clusters = groupGoalsIntoClusters(goalsByTeam.get(key) || []);
        let lastTraceability = null;
        const perGoal = clusters.map((cluster, index) => {
          const docTypeStatus = clusterCategoryStatus(cluster, DOC_TYPES);
          [cluster.primary, ...cluster.children].forEach((member) => {
            (componentsByGoal[member.id] || componentsByGoal[String(member.id)] || []).forEach((c) => {
              const updatedAt = c.mappingCreatedAt || c.createdAt;
              if (updatedAt && (!lastTraceability || new Date(updatedAt) > new Date(lastTraceability))) {
                lastTraceability = updatedAt;
              }
            });
          });
          const coveredCount = DOC_TYPES.filter((dt) => docTypeStatus[dt]).length;
          return { goalId: cluster.id, index, description: cluster.primary.description, docTypeStatus, coveredCount };
        });
        const totalCells = perGoal.length * DOC_TYPES.length;
        const coveredCount = perGoal.reduce((sum, g) => sum + g.coveredCount, 0);
        const percent = totalCells > 0 ? Math.round((coveredCount / totalCells) * 100) : 0;

        return {
          teamCode,
          section: teamSectionMap.get(key) || '',
          percent,
          coveredCount,
          totalCells,
          status: groupStatus(coveredCount, totalCells),
          lastTraceability,
          perGoal,
          readinessScore: percent,
          readinessStatus: null,
          findingCount: null,
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
