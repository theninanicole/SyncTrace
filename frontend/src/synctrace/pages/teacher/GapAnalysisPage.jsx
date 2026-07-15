import { useEffect, useMemo, useState } from 'react';
import { Download, RefreshCcw } from 'lucide-react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { getSmartGoals, getAllGoalComponents } from '../../api';
import { DOC_TYPES } from '../../hooks/useTraceability';
import './GapAnalysisPage.css';

const DOC_TYPE_SEVERITY = {
  SRS: { level: 'HIGH', label: 'Requirements (SRS)', confidence: 92 },
  SDD: { level: 'HIGH', label: 'Design (SDD)', confidence: 88 },
  SPMP: { level: 'MEDIUM', label: 'Project Management (SPMP)', confidence: 76 },
  STD: { level: 'MEDIUM', label: 'Testing (STD)', confidence: 74 },
  IMPLEMENTATION: { level: 'LOW', label: 'Implementation', confidence: 68 },
};

function IssueCard({ issue, active, onClick }) {
  return (
    <button className={`ga-issue-card ${active ? 'ga-issue-card--active' : ''}`} onClick={() => onClick(issue)}>
      <div className={`ga-issue-level ga-issue-level--${issue.level.toLowerCase()}`}>{issue.level}</div>
      <div className="ga-issue-title">{issue.title}</div>
      <div className="ga-issue-meta">{issue.tags.join(' • ')}</div>
    </button>
  );
}

function GapAnalysisPage() {
  const { toast, showToast, hideToast } = useToast();
  const [goals, setGoals] = useState([]);
  const [componentsByGoal, setComponentsByGoal] = useState({});
  const [loading, setLoading] = useState(true);
  const [selectedIssue, setSelectedIssue] = useState(null);

  useEffect(() => {
    loadGapAnalysis();
  }, []);

  async function loadGapAnalysis() {
    setLoading(true);
    try {
      const [goalList, allGoalComponents] = await Promise.all([getSmartGoals(), getAllGoalComponents()]);
      setGoals(goalList);
      setComponentsByGoal(allGoalComponents);
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoading(false);
    }
  }

  const buildGoalIssueRows = (goal, index) => {
    const goalComponents = componentsByGoal[goal.id] || [];
    const mappedByType = DOC_TYPES.reduce((acc, docType) => {
      acc[docType] = goalComponents.filter((component) => component.docType === docType);
      return acc;
    }, {});

    return DOC_TYPES.filter((docType) => mappedByType[docType].length === 0).map((docType) => {
      const severity = DOC_TYPE_SEVERITY[docType];
      return {
        id: `${goal.id}-${docType}`,
        level: severity.level,
        confidence: severity.confidence,
        title: `Missing ${severity.label} coverage for goal ${index + 1}`,
        summary: `The goal is not linked to any ${severity.label} artifacts yet. This weakens the evidence chain for evaluation and may cause traceability gaps during review.`,
        tags: [severity.label, `Goal ${index + 1}`],
        reported: goal.createdAt ? new Date(goal.createdAt).toLocaleDateString() : 'Unknown',
      };
    });
  };

  const issues = useMemo(() => {
    const gaps = goals.reduce((acc, goal, index) => {
      return acc.concat(buildGoalIssueRows(goal, index));
    }, []);
    return gaps.sort((a, b) => {
      const weight = { HIGH: 0, MEDIUM: 1, LOW: 2 };
      return weight[a.level] - weight[b.level] || a.title.localeCompare(b.title);
    });
  }, [goals, componentsByGoal]);

  useEffect(() => {
    if (!selectedIssue && issues.length > 0) {
      setSelectedIssue(issues[0]);
    }
  }, [issues, selectedIssue]);

  const counts = useMemo(() => {
    const tally = { HIGH: 0, MEDIUM: 0, LOW: 0 };
    issues.forEach((issue) => { tally[issue.level] += 1; });
    return tally;
  }, [issues]);

  const goalLabel = goals.length === 1 ? 'goal' : 'goals';
  let summaryText = 'No traceability gaps detected. All goals are connected to the tracked document types.';
  if (loading) {
    summaryText = 'Loading gap analysis...';
  } else if (issues.length > 0) {
    summaryText = `${issues.length} gap${issues.length === 1 ? '' : 's'} identified across ${goals.length} SMART ${goalLabel}.`;
  }

  return (
    <div className="ga-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Gap Analysis"
        subtitle="Review gaps in SMART goal coverage and recommended fixes"
        actions={
          <div className="teacher-header-actions">
            <button type="button" className="btn btn--soft" onClick={loadGapAnalysis} disabled={loading}>
              <RefreshCcw size={14} /> Refresh
            </button>
            <button type="button" className="btn btn--soft" onClick={() => showToast('Gap export will be available after traceability review.', 'info')}>
              <Download size={14} /> Export report
            </button>
          </div>
        }
      />

      <div className="ga-kpis">
        <div className="ga-kpi ga-kpi--high">
          <div className="ga-kpi__num">{counts.HIGH}</div>
          <div className="ga-kpi__label">High Gaps</div>
        </div>
        <div className="ga-kpi ga-kpi--medium">
          <div className="ga-kpi__num">{counts.MEDIUM}</div>
          <div className="ga-kpi__label">Medium Gaps</div>
        </div>
        <div className="ga-kpi ga-kpi--low">
          <div className="ga-kpi__num">{counts.LOW}</div>
          <div className="ga-kpi__label">Low Gaps</div>
        </div>
        <div className="ga-kpi">
          <div className="ga-kpi__num">{goals.length}</div>
          <div className="ga-kpi__label">SMART Goals</div>
        </div>
      </div>

      <div className="ga-summary">{summaryText}</div>

      <div className="ga-grid">
        <aside className="ga-sidebar">
          <div className="ga-issues-header">
            Issues <span className="ga-issues-count">{issues.length} shown</span>
          </div>
          <div className="ga-issue-list">
            {loading && <p className="tm-muted">Loading issue list...</p>}
            {!loading && issues.length === 0 && (
              <div className="empty-state"><p>All tracked goals are covered by current traceability components.</p></div>
            )}
            {!loading && issues.length > 0 && issues.map((iss) => (
              <IssueCard
                key={iss.id}
                issue={iss}
                active={selectedIssue?.id === iss.id}
                onClick={setSelectedIssue}
              />
            ))}
          </div>
        </aside>

        <section className="ga-detail">
          {selectedIssue ? (
            <>
              <div className="ga-detail__meta">
                ISSUE ANALYSIS • {selectedIssue.tags.join(' • ')}
                <span className="ga-detail__reported">REPORTED {selectedIssue.reported}</span>
              </div>
              <h2 className="ga-detail__title">{selectedIssue.title}</h2>

              <div className="ga-confidence">
                <div className="ga-confidence__label">AI confidence</div>
                <div className="ga-confidence__bar"><div className="ga-confidence__fill" style={{ width: `${selectedIssue.confidence}%` }} /></div>
                <div className="ga-confidence__value">{selectedIssue.confidence}%</div>
              </div>

              <div className="ga-section">
                <h3>Why this happened</h3>
                <p>{selectedIssue.summary}</p>
              </div>

              <div className="ga-section ga-section--positive">
                <h3>Suggested fix</h3>
                <p>
                  Add or map the missing {selectedIssue.tags[0]} artifacts to Goal {selectedIssue.tags[1].split(' ')[1]} so the traceability matrix connects this goal to the required project documentation.
                </p>
              </div>
            </>
          ) : (
            <div className="empty-state"><p>Select a gap issue to review the recommendation.</p></div>
          )}
        </section>
      </div>
    </div>
  );
}

export default GapAnalysisPage;
