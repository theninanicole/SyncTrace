import { DOC_TYPES } from '../hooks/useTraceability';

export const DOC_TYPE_SEVERITY = {
  SRS: { level: 'HIGH', label: 'Requirements (SRS)', confidence: 92 },
  SDD: { level: 'HIGH', label: 'Design (SDD)', confidence: 88 },
  SPMP: { level: 'MEDIUM', label: 'Project Management (SPMP)', confidence: 76 },
  STD: { level: 'MEDIUM', label: 'Testing (STD)', confidence: 74 },
  IMPLEMENTATION: { level: 'LOW', label: 'Implementation', confidence: 68 },
};

const LEVEL_WEIGHT = { HIGH: 0, MEDIUM: 1, LOW: 2 };

/**
 * Derives gap-analysis issues (one per missing doc type) from traceability
 * matrix rows shaped like { goalId, code, description, cells, createdAt? }.
 */
export function buildGapIssues(rows) {
  const issues = rows.flatMap((row) => {
    const goalNumber = row.code.replace(/^G/, '');
    return DOC_TYPES.filter((docType) => (row.cells[docType] || []).length === 0).map((docType) => {
      const severity = DOC_TYPE_SEVERITY[docType];
      return {
        id: `${row.goalId}-${docType}`,
        level: severity.level,
        confidence: severity.confidence,
        title: `Missing ${severity.label} coverage for goal ${goalNumber}`,
        summary: `The goal is not linked to any ${severity.label} artifacts yet. This weakens the evidence chain for evaluation and may cause traceability gaps during review.`,
        fix: `Add or map the missing ${severity.label} artifacts to Goal ${goalNumber} so the traceability matrix connects this goal to the required project documentation.`,
        tags: [severity.label, `Goal ${goalNumber}`],
        reported: row.createdAt ? new Date(row.createdAt).toLocaleDateString() : undefined,
      };
    });
  });

  return issues.sort((a, b) => LEVEL_WEIGHT[a.level] - LEVEL_WEIGHT[b.level] || a.title.localeCompare(b.title));
}
