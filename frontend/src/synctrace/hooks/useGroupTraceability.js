import { useCallback, useEffect, useState } from 'react';
import {
  getSmartGoals,
  getAllGoalComponents,
  getContinuitySummary,
  getContinuityFindings,
  getDiagnosticRecommendations,
  getContinuityAnalysisStatus,
  getLatestMappingActivity,
} from '../api';
import { isAnalysisStale } from './useTraceabilityResultsData';
import { fetchClassRoster, fetchTeacherHistory } from '../../services/dashboardService';
import { extractSubmissionMeta } from '../../utils/dashboardUtils';
import { DOC_TYPES } from './useTraceability';
import { groupStatus } from './useGroupOverview';
import { buildAiIssues } from '../utils/gapIssues';
import { groupGoalsIntoClusters } from '../constants';
import { useMappingsChangedRefresh } from '../utils/mappingEvents';

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
  aiIssues: [],
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

  const load = useCallback(async ({ silent = false } = {}) => {
    if (!teamCode) return;
    if (!silent) setGroupState((s) => ({ ...s, loading: true }));
    try {
      const [goalList, roster, history, componentsByGoal, summary, findingsData, recommendationsData, analysisStatus, activity] = await Promise.all([
        getSmartGoals(teamCode),
        fetchClassRoster().catch(() => []),
        fetchTeacherHistory().catch(() => []),
        getAllGoalComponents().catch(() => ({})),
        getContinuitySummary(teamCode).catch(() => null),
        getContinuityFindings(teamCode).catch(() => ({ findings: [] })),
        getDiagnosticRecommendations(teamCode).catch(() => ({ recommendations: [] })),
        getContinuityAnalysisStatus(teamCode).catch(() => null),
        getLatestMappingActivity(teamCode).catch(() => null),
      ]);

      const historyTeamMap = new Map();
      history.forEach((h) => {
        const meta = extractSubmissionMeta(h.fileName);
        if (meta.teamCode) historyTeamMap.set(h.id, meta.teamCode);
      });

      const section = roster.find((s) => s.groupCode?.toUpperCase() === teamCode.toUpperCase())?.section || '';

      const clusters = groupGoalsIntoClusters(goalList);

      let lastTraceability = null;
      let coveredCount = 0;

      const rows = clusters.map((cluster, clusterIndex) => {
        const cells = {};
        DOC_TYPES.forEach((dt) => { cells[dt] = []; });
        const componentById = new Map();
        const memberIds = [cluster.primary.id, ...cluster.children.map((child) => child.id)];

        memberIds.forEach((memberId) => {
          (componentsByGoal[memberId] || componentsByGoal[String(memberId)] || []).forEach((component) => {
            const componentTeam = resolveComponentTeamCode(component, historyTeamMap);
            if (componentTeam?.toUpperCase() !== teamCode.toUpperCase()) return;
            componentById.set(component.id ?? `${component.docType}:${component.name}`, component);
          });
        });

        componentById.forEach((component) => {
          if (cells[component.docType]) cells[component.docType].push(component);
          const traceabilityUpdatedAt = component.mappingCreatedAt || component.createdAt;
          if (traceabilityUpdatedAt && (!lastTraceability || new Date(traceabilityUpdatedAt) > new Date(lastTraceability))) {
            lastTraceability = traceabilityUpdatedAt;
          }
        });

        const coveredTypes = DOC_TYPES.filter((dt) => cells[dt].length > 0).length;
        coveredCount += coveredTypes;

        return {
          goalId: cluster.id,
          memberGoalIds: memberIds,
          code: `G${clusterIndex + 1}`,
          description: cluster.primary.description,
          specificDescriptions: cluster.children.map((child) => child.description),
          teamCode: cluster.primary.teamCode || '',
          cells,
          aligned: coveredTypes === DOC_TYPES.length,
          createdAt: cluster.primary.createdAt,
        };
      });

      const totalCells = rows.length * DOC_TYPES.length;
      const percent = totalCells > 0 ? Math.round((coveredCount / totalCells) * 100) : 0;

      const goalCodeById = new Map(rows.map((r) => [r.goalId, r.code]));
      rows.forEach((row) => row.memberGoalIds.forEach((memberId) => goalCodeById.set(memberId, row.code)));
      // An analysis made before the mapping last changed no longer describes it.
      const aiIssues = isAnalysisStale(analysisStatus, activity)
        ? []
        : buildAiIssues(findingsData.findings || [], recommendationsData.recommendations || [], goalCodeById);

      setGroupState((s) => ({
        ...s,
        section,
        rows,
        percent,
        status: summary ? mapBackendStatus(summary.status) : groupStatus(coveredCount, totalCells),
        coveredCount,
        totalCells,
        lastTraceability,
        readinessScore: percent,
        readinessStatus: summary?.status ?? null,
        findingCount: summary?.totalFindings ?? 0,
        aiIssues,
      }));
    } catch (err) {
      if (!silent) showToast?.(err.message, 'error');
    } finally {
      if (!silent) setGroupState((s) => ({ ...s, loading: false }));
    }
  }, [teamCode, showToast]);

  useEffect(() => {
    load();
  }, [load]);

  const reloadSilently = useCallback(() => load({ silent: true }), [load]);
  useMappingsChangedRefresh(teamCode, reloadSilently);

  return { ...state, reload: () => load() };
}
