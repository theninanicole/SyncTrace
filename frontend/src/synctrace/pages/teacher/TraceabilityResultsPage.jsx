import { useState } from 'react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { useSelectedTeam } from '../../hooks/useSelectedTeam';
import { useTraceabilityResultsData } from '../../hooks/useTraceabilityResultsData';
import { useAiContinuityAnalysis } from '../../hooks/useAiContinuityAnalysis';
import TraceabilityResults from '../../components/common/TraceabilityResults';
import ComponentDetailModal from '../../components/teacher/ComponentDetailModal';
import ExportReportButton from '../../components/common/ExportReportButton';
import TeamSelect from '../../components/common/TeamSelect';
import './SourceCodePage.css';
import './TraceabilityResultsPage.css';

function TraceabilityResultsPage({ onNavigate }) {
  const { toast, showToast, hideToast } = useToast();
  const [selectedTeam, setSelectedTeam] = useSelectedTeam();
  const [previewComponent, setPreviewComponent] = useState(null);

  const {
    loading,
    rows,
    metrics,
    aiIssues,
    analysisStatus,
    setAiFindings,
    setAiRecommendations,
    refresh: loadResults,
  } = useTraceabilityResultsData(selectedTeam, { showToast });

  const {
    running: runningAiAnalysis,
    progress: aiProgress,
    run: handleRunAiAnalysis,
  } = useAiContinuityAnalysis(selectedTeam, { showToast, setAiFindings, setAiRecommendations, onComplete: loadResults });

  return (
    <div className="tp-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Traceability Results"
        subtitle={selectedTeam
          ? `Traceability coverage for ${selectedTeam}`
          : 'Traceability coverage across SMART goals and project artifacts'}
        actions={
          <div className="teacher-header-actions">
            <button
              className="btn btn--soft"
              onClick={handleRunAiAnalysis}
              disabled={runningAiAnalysis}
            >
              {runningAiAnalysis ? 'Analyzing...' : 'Run AI Analysis'}
            </button>
            <ExportReportButton showToast={showToast} teamCode={selectedTeam} />
          </div>
        }
      />

      <div className="tm-team-filter-row">
        <TeamSelect value={selectedTeam} onChange={setSelectedTeam} />
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

      <div className="tp-kpi-row">
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

      <div className="tp-summary">
        <div className="tp-summary__text">{metrics.missingCells} traceability gap{metrics.missingCells !== 1 ? 's' : ''} remain across {rows.length} goal{rows.length !== 1 ? 's' : ''}.</div>
        <div className="tp-summary__actions" />
      </div>

      <TraceabilityResults
        loading={loading}
        rows={rows}
        onComponentClick={setPreviewComponent}
        onAddClick={(row, docType) => onNavigate?.('traceability', {
          focusGoalId: row.goalId,
          focusStep: 'map',
          focusDocType: docType,
        })}
        aiIssues={aiIssues}
        analysisStatus={analysisStatus}
      />

      <ComponentDetailModal
        component={previewComponent}
        onClose={() => setPreviewComponent(null)}
        onRenamed={(updated) => { setPreviewComponent(updated); loadResults(); }}
        showToast={showToast}
      />
    </div>
  );
}

export default TraceabilityResultsPage;
