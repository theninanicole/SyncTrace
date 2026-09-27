import { useCallback, useEffect, useMemo, useState } from 'react';
import { fetchClassRoster } from '../../services/dashboardService';
import { useTraceabilityResultsData } from './useTraceabilityResultsData';
import { groupStatus } from './useGroupOverview';
import { DOC_TYPES } from '../constants';

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

/**
 * Group (per-team) view of the traceability results. Rows, findings and analysis status
 * come from the same hook the Traceability Results page uses, so both pages always agree.
 * Previously this hook rebuilt the matrix itself and filtered components through the
 * teacher's evaluation history on the client; whenever that history request failed or
 * was slow every cell rendered as "Missing", and the status column never saw the
 * analysis run, which made results look different from one visit to the next.
 */
export function useGroupTraceability(teamCode, showToast) {
  const {
    loading,
    rows,
    aiIssues,
    analysisStatus,
    summary,
    refresh,
  } = useTraceabilityResultsData(teamCode, { showToast, enabled: Boolean(teamCode), includeSummary: true });

  const [section, setSection] = useState('');
  useEffect(() => {
    let cancelled = false;
    if (!teamCode) return undefined;
    fetchClassRoster()
      .catch(() => [])
      .then((roster) => {
        if (cancelled) return;
        const match = roster.find((s) => s.groupCode?.toUpperCase() === teamCode.toUpperCase());
        setSection(match?.section || '');
      });
    return () => { cancelled = true; };
  }, [teamCode]);

  const derived = useMemo(() => {
    let coveredCount = 0;
    let lastTraceability = null;
    rows.forEach((row) => {
      coveredCount += DOC_TYPES.filter((dt) => (row.cells[dt] || []).length > 0).length;
      DOC_TYPES.forEach((dt) => (row.cells[dt] || []).forEach((component) => {
        const updatedAt = component.mappingCreatedAt || component.createdAt;
        if (updatedAt && (!lastTraceability || new Date(updatedAt) > new Date(lastTraceability))) {
          lastTraceability = updatedAt;
        }
      }));
    });
    const totalCells = rows.length * DOC_TYPES.length;
    const percent = totalCells > 0 ? Math.round((coveredCount / totalCells) * 100) : 0;
    return { coveredCount, totalCells, percent, lastTraceability };
  }, [rows]);

  const reload = useCallback(() => refresh(), [refresh]);

  return {
    loading,
    section,
    rows,
    ...derived,
    status: summary ? mapBackendStatus(summary.status) : groupStatus(derived.coveredCount, derived.totalCells),
    // The backend score is computed from the same clusters and findings shown in the matrix.
    readinessScore: typeof summary?.readinessScore === 'number' ? summary.readinessScore : derived.percent,
    readinessStatus: summary?.status ?? null,
    findingCount: summary?.totalFindings ?? 0,
    aiIssues,
    analysisStatus,
    reload,
  };
}
