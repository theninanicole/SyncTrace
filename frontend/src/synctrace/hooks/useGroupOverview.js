import { useCallback, useEffect, useState } from 'react';
import { getSmartGoals, getAllGoalComponents, getContinuitySummary } from '../api';
import { fetchClassRoster, fetchTeacherHistory, fetchTeacherSubmissions } from '../../services/dashboardService';
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

export function useGroupOverview(showToast) {
  const [goals, setGoals] = useState([]);
  const [groups, setGroups] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [goalList, roster, history, componentsByGoal, submissions] = await Promise.all([
        getSmartGoals(),
        fetchClassRoster().catch(() => []),
        fetchTeacherHistory().catch(() => []),
        getAllGoalComponents().catch(() => ({})),
        fetchTeacherSubmissions().catch(() => []),
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
      
      // First, add teams from roster if available
      roster.forEach((s) => {
        if (!s.groupCode) return;
        const key = s.groupCode.toUpperCase();
        teamKeys.add(key);
        if (!displayCodeByKey.has(key)) displayCodeByKey.set(key, s.groupCode);
        if (s.section && !teamSectionMap.has(key)) teamSectionMap.set(key, s.section);
      });
      
      // Add teams from submissions as well
      submissions.forEach((s) => {
        const meta = extractSubmissionMeta(s.name);
        if (meta.teamCode) {
          const key = meta.teamCode.toUpperCase();
          teamKeys.add(key);
          if (!displayCodeByKey.has(key)) displayCodeByKey.set(key, meta.teamCode);
          if (meta.section && !teamSectionMap.has(key)) teamSectionMap.set(key, meta.section);
        }
      });

      const perGoalComponents = goalList.map((g) => componentsByGoal[g.id] || []);

      const coverageByTeam = new Map();
      const lastTraceabilityByTeam = new Map();
      goalList.forEach((goal, gi) => {
        (perGoalComponents[gi] || []).forEach((c) => {
          const teamCode = resolveComponentTeamCode(c, historyTeamMap);
          if (!teamCode) return;
          const key = teamCode.toUpperCase();
          teamKeys.add(key); // Add team from components as well
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

      const summariesByKey = new Map();
      await Promise.all([...teamKeys].map(async (key) => {
        try {
          const teamCode = displayCodeByKey.get(key) || key;
          const summary = await getContinuitySummary(teamCode);
          summariesByKey.set(key, summary);
        } catch {
          // Keep local fallback if summary is temporarily unavailable.
        }
      }));

      const groupList = [...teamKeys].sort((a, b) => a.localeCompare(b)).map((key) => {
        const teamCode = displayCodeByKey.get(key) || key;
        const covered = coverageByTeam.get(key) || new Set();
        const summary = summariesByKey.get(key);
        const perGoal = goalList.map((goal, gi) => {
          const docTypeStatus = {};
          DOC_TYPES.forEach((dt) => { docTypeStatus[dt] = covered.has(`${goal.id}:${dt}`); });
          const coveredCount = DOC_TYPES.filter((dt) => docTypeStatus[dt]).length;
          return { goalId: goal.id, index: gi, description: goal.description, docTypeStatus, coveredCount };
        });
        const coveredCount = summary
          ? summary.goalSummaries.reduce((sum, goalSummary) => sum + goalSummary.coveredDocTypes.length, 0)
          : perGoal.reduce((sum, g) => sum + g.coveredCount, 0);
        const summaryTotalCells = summary ? summary.totalGoals * DOC_TYPES.length : totalCells;
        const percent = summaryTotalCells > 0 ? Math.round((coveredCount / summaryTotalCells) * 100) : 0;

        return {
          teamCode,
          section: teamSectionMap.get(key) || '',
          percent,
          coveredCount,
          totalCells: summaryTotalCells,
          status: summary ? mapBackendStatus(summary.status) : groupStatus(coveredCount, totalCells),
          lastTraceability: lastTraceabilityByTeam.get(key) || null,
          perGoal,
          readinessScore: summary?.readinessScore ?? percent,
          readinessStatus: summary?.status ?? null,
          findingCount: summary?.totalFindings ?? 0,
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
