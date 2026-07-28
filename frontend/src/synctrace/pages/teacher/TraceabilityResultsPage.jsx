import { useState } from 'react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { detectContinuityGaps, generateDiagnosticRecommendations } from '../../api';
import { API_BASE_URL } from '../../../api';
import { useSelectedTeam } from '../../hooks/useSelectedTeam';
import { useTraceabilityResultsData } from '../../hooks/useTraceabilityResultsData';
import TraceabilityResults from '../../components/common/TraceabilityResults';
import ComponentDetailModal from '../../components/teacher/ComponentDetailModal';
import SendButton from '../../components/common/SendButton';
import ExportReportButton from '../../components/common/ExportReportButton';
import TeamSelect from '../../components/common/TeamSelect';
import './SourceCodePage.css';
import './TraceabilityResultsPage.css';

function TraceabilityResultsPage({ onNavigate }) {
  const { toast, showToast, hideToast } = useToast();
  const [selectedTeam, setSelectedTeam] = useSelectedTeam();
  const [previewComponent, setPreviewComponent] = useState(null);

  const [runningAiAnalysis, setRunningAiAnalysis] = useState(false);
  const [aiProgress, setAiProgress] = useState({ step: '', message: '', percent: 0 });
  const {
    loading,
    rows,
    metrics,
    aiIssues,
    setAiFindings,
    setAiRecommendations,
    refresh: loadResults,
  } = useTraceabilityResultsData(selectedTeam, { showToast });

  function generateSessionId() {
    return crypto.randomUUID
      ? crypto.randomUUID()
      : Math.random().toString(36).slice(2) + Date.now().toString(36);
  }

  async function handleRunAiAnalysis() {
    if (!selectedTeam) {
      showToast('Select a team above to run AI analysis.', 'error');
      return;
    }

    const sessionId = generateSessionId();
    setRunningAiAnalysis(true);
    setAiProgress({ step: 'RECEIVED', message: 'Starting AI continuity analysis...', percent: 0 });
    setAiFindings([]);
    setAiRecommendations([]);

    const es = new EventSource(`${API_BASE_URL}/ai/progress/${sessionId}`);

    es.addEventListener('progress', (e) => {
      try {
        const { step, message, percent } = JSON.parse(e.data);
        setAiProgress({ step, message, percent });
      } catch { /* ignore parse errors */ }
    });

    es.addEventListener('done', () => {
      es.close();
    });

    es.addEventListener('error', (e) => {
      try {
        const { error: msg } = JSON.parse(e.data || '{}');
        if (msg) showToast(`AI analysis error: ${msg}`, 'error');
      } catch { /* ignore */ }
      es.close();
    });

    es.onerror = () => {
      es.close();
    };

    try {
      // Both detectContinuityGaps and generateDiagnosticRecommendations are scoped to a
      // single team on the backend: gap detection only counts components/mappings that
      // belong to teamCode (goals with none are skipped), and recommendations are generated
      // only from findings already tagged with that teamCode. Always analyze the team
      // selected above, not some other team the teacher isn't looking at.
      setAiProgress({ step: 'DETECTING', message: 'Detecting continuity gaps...', percent: 30 });
      const gapsData = await detectContinuityGaps(selectedTeam, null);
      setAiFindings(gapsData.findings || []);

      setAiProgress({ step: 'RECOMMENDING', message: 'Generating diagnostic recommendations...', percent: 60 });
      const recommendationsData = await generateDiagnosticRecommendations(selectedTeam, 'auto', sessionId);
      setAiRecommendations(recommendationsData.recommendations || []);

      setAiProgress({ step: 'COMPLETE', message: 'AI analysis complete', percent: 100 });

      if (gapsData.findings.length === 0 && recommendationsData.recommendations.length === 0) {
        showToast('AI analysis complete. No continuity gaps or recommendations found.', 'success');
      } else {
        showToast(`AI analysis complete. Found ${gapsData.count} gap(s) and ${recommendationsData.count} recommendation(s).`, 'success');
      }
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setRunningAiAnalysis(false);
      es.close();
    }
  }

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
            <SendButton showToast={showToast} teamCode={selectedTeam} />
            <ExportReportButton showToast={showToast} />
            <SendButton showToast={showToast} />
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
