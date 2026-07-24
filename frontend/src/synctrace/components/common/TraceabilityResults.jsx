import { useMemo, useState } from 'react';
import TraceabilityMatrixTable from './TraceabilityMatrix';
import IssueAnalysisPanel from './IssueAnalysisPanel';
import { buildGapIssues, buildRowDiagnosis } from '../../utils/gapIssues';
import '../../pages/teacher/TraceabilityMappingPage.css';
import './TraceabilityResults.css';

function TraceabilityResults({ loading, rows, onComponentClick, onAddClick, issues: externalIssues }) {
  const enrichedRows = useMemo(
    () => rows.map((row) => ({
      ...row,
      diagnosis: row.diagnosis ?? buildRowDiagnosis(row),
    })),
    [rows],
  );

  const issues = useMemo(() => externalIssues ?? buildGapIssues(enrichedRows), [externalIssues, enrichedRows]);
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
            emptyMessage="No SMART goals yet."
          />

          {enrichedRows.length > 0 && (
            <IssueAnalysisPanel
              issues={issues}
              selectedIssue={selectedIssue}
              onSelectIssue={(issue) => setSelectedIssueId(issue.id)}
              issuesEmptyMessage="No traceability gaps detected."
            />
          )}
        </>
      )}
    </div>
  );
}

export default TraceabilityResults;
