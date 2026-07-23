import { DOC_TYPES, isPreferredArtifactKind, preferredArtifactHint } from '../constants';

export const DOC_TYPE_SEVERITY = {
  SRS: { level: 'HIGH', label: 'Requirements (SRS)', confidence: 92 },
  SDD: { level: 'HIGH', label: 'Design (SDD)', confidence: 88 },
  SPMP: { level: 'MEDIUM', label: 'Project Management (SPMP)', confidence: 76 },
  STD: { level: 'MEDIUM', label: 'Testing (STD)', confidence: 74 },
  IMPLEMENTATION: { level: 'LOW', label: 'Implementation', confidence: 68 },
};

const LEVEL_WEIGHT = { HIGH: 0, MEDIUM: 1, LOW: 2 };

/**
 * Derives gap-analysis issues (missing doc types + goal-kind alignment warnings)
 * from matrix rows shaped like { goalId, code, description, goalKind, cells, createdAt? }.
 */
export function buildGapIssues(rows) {
  const issues = rows.flatMap((row) => {
    const goalNumber = String(row.code || '').replace(/^G-?/i, '') || '?';
    const goalKind = row.goalKind || 'SPECIFIC';
    const missing = DOC_TYPES.filter((docType) => (row.cells[docType] || []).length === 0).map((docType) => {
      const severity = DOC_TYPE_SEVERITY[docType];
      return {
        id: `${row.goalId}-${docType}`,
        level: severity.level,
        confidence: severity.confidence,
        title: `Missing ${severity.label} coverage for goal ${goalNumber}`,
        summary: `The goal is not linked to any ${severity.label} artifacts yet. This weakens the evidence chain for evaluation and may cause traceability gaps during review.`,
        fix: `Directly translate Goal ${goalNumber} into the matching ${severity.label} components (use cases, classes, tasks, tests, or implementation units).`,
        tags: [severity.label, `Goal ${goalNumber}`, goalKind],
        reported: row.createdAt ? new Date(row.createdAt).toLocaleDateString() : undefined,
      };
    });

    const mapped = DOC_TYPES.flatMap((dt) => row.cells[dt] || []);
    if (mapped.length === 0) return missing;

    const hasPreferred = mapped.some((c) => isPreferredArtifactKind(goalKind, c.artifactKind));
    if (hasPreferred) return missing;

    missing.push({
      id: `${row.goalId}-kind-align`,
      level: 'MEDIUM',
      confidence: 70,
      title: goalKind === 'GENERAL'
        ? `Goal ${goalNumber} lacks module-level artifact kinds`
        : `Goal ${goalNumber} lacks function/transaction artifact kinds`,
      summary: preferredArtifactHint(goalKind),
      fix: goalKind === 'GENERAL'
        ? `Map Goal ${goalNumber} to module-oriented components (context/data modules, classes, milestones, deliverables).`
        : `Map Goal ${goalNumber} to function/transaction components (use cases, activities, sequences, UI, test cases).`,
      tags: [`Goal ${goalNumber}`, goalKind, 'Kind alignment'],
      reported: row.createdAt ? new Date(row.createdAt).toLocaleDateString() : undefined,
    });

    return missing;
  });

  return issues.sort((a, b) => LEVEL_WEIGHT[a.level] - LEVEL_WEIGHT[b.level] || a.title.localeCompare(b.title));
}

/** Short inline diagnosis for a matrix row (shown under the goal). */
export function buildRowDiagnosis(row) {
  const goalKind = row.goalKind || 'SPECIFIC';
  const missing = DOC_TYPES.filter((docType) => (row.cells[docType] || []).length === 0);
  const mapped = DOC_TYPES.flatMap((dt) => row.cells[dt] || []);

  if (mapped.length === 0) {
    return 'No artifacts mapped yet. Add SRS, SDD, SPMP, STD, and CODE links for this goal.';
  }

  if (missing.length > 0) {
    const labels = missing.map((dt) => (dt === 'IMPLEMENTATION' ? 'CODE' : dt));
    const primary = labels[0];
    const tip = primary === 'STD'
      ? 'Recommend adding a test case (e.g. TC-xx).'
      : primary === 'CODE'
        ? 'Recommend linking an implementation file from GitHub ingest.'
        : `Recommend mapping a ${primary} component.`;
    return `${labels.join(', ')} coverage is missing. ${tip}`;
  }

  const hasPreferred = mapped.some((c) => isPreferredArtifactKind(goalKind, c.artifactKind));
  if (!hasPreferred) {
    return preferredArtifactHint(goalKind);
  }

  return null;
}
