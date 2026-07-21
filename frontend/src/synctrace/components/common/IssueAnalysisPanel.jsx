import { useMemo, useState } from 'react';
import './IssueAnalysisPanel.css';

const LEVEL_FILTERS = [
  { key: 'ALL', label: 'All' },
  { key: 'HIGH', label: 'High' },
  { key: 'MEDIUM', label: 'Medium' },
  { key: 'LOW', label: 'Low' },
];

function IssueCard({ issue, active, onClick }) {
  return (
    <button className={`iap-issue-card ${active ? 'iap-issue-card--active' : ''}`} onClick={() => onClick(issue)}>
      <div className={`iap-issue-level iap-issue-level--${issue.level.toLowerCase()}`}>{issue.level}</div>
      <div className="iap-issue-title">{issue.title}</div>
      <div className="iap-issue-meta">{issue.tags.join(' • ')}</div>
    </button>
  );
}

function IssueAnalysisPanel({
  issues,
  selectedIssue,
  onSelectIssue,
  loading = false,
  issuesEmptyMessage = 'No issues detected.',
  detailEmptyMessage = 'Select an issue to review the recommendation.',
}) {
  const [levelFilter, setLevelFilter] = useState('ALL');

  const counts = useMemo(() => {
    const tally = { ALL: issues.length, HIGH: 0, MEDIUM: 0, LOW: 0 };
    issues.forEach((issue) => { tally[issue.level] += 1; });
    return tally;
  }, [issues]);

  const filteredIssues = levelFilter === 'ALL' ? issues : issues.filter((issue) => issue.level === levelFilter);

  return (
    <div className="iap-grid">
      <aside className="iap-sidebar">
        <div className="iap-issues-header">
          Issues <span className="iap-issues-count">{filteredIssues.length} of {issues.length} shown</span>
        </div>

        {issues.length > 0 && (
          <div className="iap-filter-row">
            {LEVEL_FILTERS.map((f) => (
              <button
                type="button"
                key={f.key}
                className={`iap-filter-chip ${levelFilter === f.key ? 'iap-filter-chip--active' : ''}`}
                onClick={() => setLevelFilter(f.key)}
              >
                {f.label} ({counts[f.key]})
              </button>
            ))}
          </div>
        )}

        <div className="iap-issue-list">
          {loading && <p className="tm-muted">Loading issue list...</p>}
          {!loading && filteredIssues.length === 0 && (
            <div className="empty-state">
              <p>{issues.length === 0 ? issuesEmptyMessage : `No ${levelFilter.toLowerCase()} severity issues.`}</p>
            </div>
          )}
          {!loading && filteredIssues.length > 0 && filteredIssues.map((iss) => (
            <IssueCard
              key={iss.id}
              issue={iss}
              active={selectedIssue?.id === iss.id}
              onClick={onSelectIssue}
            />
          ))}
        </div>
      </aside>

      <section className="iap-detail">
        {selectedIssue ? (
          <>
            <div className="iap-detail__meta">
              ISSUE ANALYSIS • {selectedIssue.tags.join(' • ')}
              {selectedIssue.reported && (
                <span className="iap-detail__reported">REPORTED {selectedIssue.reported}</span>
              )}
            </div>
            <h2 className="iap-detail__title">{selectedIssue.title}</h2>

            <div className="iap-confidence">
              <div className="iap-confidence__label">Severity</div>
              <div className="iap-confidence__bar"><div className="iap-confidence__fill" style={{ width: `${selectedIssue.confidence}%` }} /></div>
              <div className="iap-confidence__value">{selectedIssue.confidence}%</div>
            </div>

            <div className="iap-section">
              <h3>Why this happened</h3>
              <p>{selectedIssue.summary}</p>
            </div>

            {selectedIssue.fix && (
              <div className="iap-section iap-section--positive">
                <h3>Suggested fix</h3>
                <p>{selectedIssue.fix}</p>
              </div>
            )}
          </>
        ) : (
          <div className="empty-state"><p>{detailEmptyMessage}</p></div>
        )}
      </section>
    </div>
  );
}

export default IssueAnalysisPanel;
