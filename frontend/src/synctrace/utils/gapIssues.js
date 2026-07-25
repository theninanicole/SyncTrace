import { DOC_TYPES, isPreferredArtifactKind, preferredArtifactHint } from '../constants';

export const DOC_TYPE_SEVERITY = {
  SRS: { level: 'HIGH', label: 'Requirements (SRS)', confidence: 92 },
  SDD: { level: 'HIGH', label: 'Design (SDD)', confidence: 88 },
  SPMP: { level: 'MEDIUM', label: 'Project Management (SPMP)', confidence: 76 },
  STD: { level: 'MEDIUM', label: 'Testing (STD)', confidence: 74 },
  IMPLEMENTATION: { level: 'LOW', label: 'Implementation', confidence: 68 },
};

export const LEVEL_WEIGHT = { HIGH: 0, MEDIUM: 1, LOW: 2 };

const AI_SEVERITY_CONFIDENCE = { CRITICAL: 97, HIGH: 90, MEDIUM: 75, LOW: 60 };

function docTypeLabel(docType) {
  return DOC_TYPE_SEVERITY[docType]?.label || (docType === 'PROPOSAL' ? 'Proposal' : docType);
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

/**
 * Converts backend ContinuityFinding + DiagnosticRecommendation records (from
 * /synctrace/continuity/detect-gaps and /synctrace/continuity/recommendations) into
 * the issue shape the Issues panel expects.
 */
export function buildAiIssues(findings, recommendations, goalCodeById = new Map()) {
  const recsByFindingId = new Map();
  recommendations.forEach((rec) => {
    if (!recsByFindingId.has(rec.findingId)) recsByFindingId.set(rec.findingId, []);
    recsByFindingId.get(rec.findingId).push(rec);
  });

  return findings.map((finding) => {
    const rawLevel = ['HIGH', 'MEDIUM', 'LOW'].includes(finding.severity) ? finding.severity : 'HIGH';
    const goalCode = goalCodeById.get(finding.goalId) || null;
    const fromLabel = docTypeLabel(finding.docTypeFrom);
    const toLabel = docTypeLabel(finding.docTypeTo);
    const matchedRecs = recsByFindingId.get(finding.id) || [];
    const fix = matchedRecs.map((r) => r.recommendation).filter(Boolean).join(' ') || undefined;

    return {
      id: `ai-${finding.id}`,
      level: rawLevel,
      confidence: AI_SEVERITY_CONFIDENCE[finding.severity] ?? AI_SEVERITY_CONFIDENCE[rawLevel],
      title: `Continuity gap: ${fromLabel} → ${toLabel}${goalCode ? ` (${goalCode})` : ''}`,
      summary: finding.description || 'The AI continuity check found a broken link between these artifacts.',
      fix,
      tags: ['AI Verified', goalCode, finding.teamCode].filter(Boolean),
      reported: finding.detectedAt ? new Date(finding.detectedAt).toLocaleDateString() : undefined,
    };
  });
}
