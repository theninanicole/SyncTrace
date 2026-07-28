import { useMemo, useState } from 'react';
import TraceabilityMatrixTable from './TraceabilityMatrix';
import IssueAnalysisPanel from './IssueAnalysisPanel';
import { buildRowDiagnosis, LEVEL_WEIGHT } from '../../utils/gapIssues';
import '../../pages/teacher/TraceabilityMappingPage.css';
import './TraceabilityResults.css';

function TraceabilityResults({
  loading,
  rows,
  onComponentClick,
  onAddClick,
  aiIssues = [],
  issuesEmptyMessage = 'Run AI Analysis to detect continuity gaps.',
  readOnly = false,
}) {
  const enrichedRows = useMemo(
    () => rows.map((row) => ({
      ...row,
      diagnosis: row.diagnosis ?? buildRowDiagnosis(row),
    })),
    [rows],
  );

  // The Issues panel only ever reflects AI Analysis output — it stays empty until
  // the teacher runs it, rather than eagerly flagging missing cells on its own.
  const issues = useMemo(() => (
    [...aiIssues].sort(
      (a, b) => LEVEL_WEIGHT[a.level] - LEVEL_WEIGHT[b.level] || a.title.localeCompare(b.title),
    )
  ), [aiIssues]);
  const [selectedIssueId, setSelectedIssueId] = useState(null);
  const selectedIssue = issues.find((issue) => issue.id === selectedIssueId) ?? issues[0] ?? null;

  return (
    <div className="tr-results">
      {loading ? (
        <p className="tm-muted">Loading traceability matrix...</p>
      ) : (
        <>
          <TraceabilityMatrixTable
            rows={enrichedRows}
            onComponentClick={onComponentClick}
            onAddClick={onAddClick}
            emptyMessage="No traceability data available."
            readOnly={readOnly}
          />

          {enrichedRows.length > 0 && (
            <IssueAnalysisPanel
              issues={issues}
              selectedIssue={selectedIssue}
              onSelectIssue={(issue) => setSelectedIssueId(issue.id)}
              issuesEmptyMessage={issuesEmptyMessage}
            />
          )}
        </>
      )}
    </div>
  );
}

export default TraceabilityResults;
