import { useState } from 'react';
import TraceabilityResults from '../../synctrace/components/common/TraceabilityResults';
import ComponentDetailModal from '../../synctrace/components/teacher/ComponentDetailModal';
import { useTraceabilityResultsData } from '../../synctrace/hooks/useTraceabilityResultsData';
import { useAiContinuityAnalysis } from '../../synctrace/hooks/useAiContinuityAnalysis';
import ToastMessage from '../common/ToastMessage';
import { useToast } from '../../hooks/useToast';
import '../../synctrace/pages/teacher/SourceCodePage.css';
import '../../synctrace/pages/teacher/TraceabilityResultsPage.css';

function StudentTraceabilityResults({ teamCode }) {
  const { toast, showToast, hideToast } = useToast();
  const [previewComponent, setPreviewComponent] = useState(null);
  const {
    loading,
    rows,
    metrics,
    aiIssues,
    analysisStatus,
    setAiFindings,
    setAiRecommendations,
    refresh,
  } = useTraceabilityResultsData(teamCode, { enabled: Boolean(teamCode), showToast });

  const {
    running: runningAiAnalysis,
    progress: aiProgress,
    run: runAiAnalysis,
  } = useAiContinuityAnalysis(teamCode, { showToast, setAiFindings, setAiRecommendations, onComplete: refresh });

  return (
    <section className="card student-traceability-card">
      <ToastMessage toast={toast} onClose={hideToast} />
      <div className="student-section-heading">
        <div>
          <h2 className="card__title">Traceability Results</h2>
          <p className="student-section-heading__subtitle">
            {teamCode
              ? `Coverage for ${teamCode} across SMART goals, documents, and implementation.`
              : 'Traceability coverage will appear after your team is connected.'}
          </p>
        </div>
        <div className="student-section-heading__actions">
          <button
            className="btn btn--soft"
            type="button"
            onClick={runAiAnalysis}
            disabled={runningAiAnalysis || loading || !teamCode}
          >
            {runningAiAnalysis ? 'Analyzing...' : 'Run AI Analysis'}
          </button>
          <button className="btn btn--soft" type="button" onClick={refresh} disabled={loading || runningAiAnalysis || !teamCode}>
            {loading ? 'Loading...' : 'Refresh'}
          </button>
        </div>
      </div>

      {runningAiAnalysis && (
        <div className="sc-progress" style={{ marginBottom: '1rem' }}>
          <div className="sc-progress-bar">
            <div className="sc-progress-fill" style={{ width: `${aiProgress.percent}%` }} />
          </div>
          <div className="sc-progress-text">
            {aiProgress.step}: {aiProgress.message} ({aiProgress.percent}%)
          </div>
        </div>
      )}

      {!teamCode ? (
        <div className="student-traceability-locked">
          <h3 className="student-empty__title">No team connected</h3>
          <p className="student-empty__text">Traceability results will appear here once your team is connected.</p>
        </div>
      ) : (
        <>
          <div className="tp-kpi-row student-traceability-kpis">
            <div className="tp-kpi tp-kpi--ok">
              <div className="tp-kpi__value">{metrics.alignmentPercent}%</div>
              <div className="tp-kpi__label">Alignment</div>
              <div className="tp-kpi__subtitle">Coverage across goals</div>
            </div>
            <div className="tp-kpi">
              <div className="tp-kpi__value">{metrics.unmappedGoals}</div>
              <div className="tp-kpi__label">Unmapped Goals</div>
              <div className="tp-kpi__subtitle">No traceability found</div>
            </div>
            <div className="tp-kpi">
              <div className="tp-kpi__value">{metrics.partialGoals}</div>
              <div className="tp-kpi__label">Partial Goals</div>
              <div className="tp-kpi__subtitle">Some document types mapped</div>
            </div>
            <div className="tp-kpi">
              <div className="tp-kpi__value">{metrics.missingCells}</div>
              <div className="tp-kpi__label">Missing Cells</div>
              <div className="tp-kpi__subtitle">Required traceability gaps</div>
            </div>
            <div className="tp-kpi">
              <div className="tp-kpi__value">{metrics.fullyCoveredGoals}</div>
              <div className="tp-kpi__label">Fully Covered</div>
              <div className="tp-kpi__subtitle">Goals with all doc types</div>
            </div>
          </div>

          <div className="tp-summary student-traceability-summary">
            <div className="tp-summary__text">
              {metrics.missingCells} traceability gap{metrics.missingCells !== 1 ? 's' : ''} remain across {rows.length} goal{rows.length !== 1 ? 's' : ''}.
            </div>
          </div>

          <TraceabilityResults
            loading={loading}
            rows={rows}
            onComponentClick={setPreviewComponent}
            aiIssues={aiIssues}
            analysisStatus={analysisStatus}
            issuesEmptyMessage="No continuity issues for your team yet."
            readOnly
          />

          <ComponentDetailModal
            component={previewComponent}
            onClose={() => setPreviewComponent(null)}
            readOnly
          />
        </>
      )}
    </section>
  );
}

export default StudentTraceabilityResults;
