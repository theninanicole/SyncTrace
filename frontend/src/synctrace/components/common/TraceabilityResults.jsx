import { useMemo, useState } from 'react';
import TraceabilityMatrixTable from './TraceabilityMatrix';
import IssueAnalysisPanel from './IssueAnalysisPanel';
import { buildGapIssues } from '../../utils/gapIssues';
import '../../pages/teacher/TraceabilityMappingPage.css';
import './TraceabilityResults.css';

function TraceabilityResults({ loading, rows, onComponentClick, issues: externalIssues }) {
  const issues = useMemo(() => externalIssues ?? buildGapIssues(rows), [externalIssues, rows]);
  const [selectedIssueId, setSelectedIssueId] = useState(null);

  const selectedIssue = issues.find((issue) => issue.id === selectedIssueId) ?? issues[0] ?? null;

  return (
    <div className="tr-results">
      {loading ? (
        <p className="tm-muted">Loading traceability matrix...</p>
      ) : (
        <>
          <TraceabilityMatrixTable
            rows={rows}
            onComponentClick={onComponentClick}
            emptyMessage="No SMART goals yet."
          />

          {rows.length > 0 && (
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
