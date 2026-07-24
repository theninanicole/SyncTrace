import { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowLeft, Download, Sparkles, Loader2 } from 'lucide-react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { formatDate } from '../../../utils/dashboardUtils';
import { useGroupTraceability } from '../../hooks/useGroupTraceability';
import { STATUS_META } from '../../hooks/useGroupOverview';
import ComponentDetailModal from '../../components/teacher/ComponentDetailModal';
import TraceabilityResults from '../../components/common/TraceabilityResults';
import SendButton from '../../components/common/SendButton';
import ExportReportButton from '../../components/common/ExportReportButton';
import { detectContinuityGaps, generateDiagnosticRecommendations, getContinuityFindings, getDiagnosticRecommendations } from '../../api';
import { API_BASE_URL } from '../../../api';
import './TraceabilityMappingPage.css';
import './GroupTraceabilityPage.css';

const SEVERITY_CONFIDENCE = {
  CRITICAL: 98,
  HIGH: 90,
  MEDIUM: 75,
  LOW: 60,
};

function GroupTraceabilityPage({ teamCode, onBack }) {
  const { toast, showToast, hideToast } = useToast();
  const { loading, section, rows, status, lastTraceability, readinessScore, readinessStatus, findingCount, reload } = useGroupTraceability(teamCode, showToast);
  const [previewComponent, setPreviewComponent] = useState(null);
  //eslint-disable-next-line no-unused-vars
  const meta = STATUS_META[status];
  
  const [runningAiAnalysis, setRunningAiAnalysis] = useState(false);
  const [aiProgress, setAiProgress] = useState({ step: '', message: '', percent: 0 });
  const [aiFindings, setAiFindings] = useState([]);
  const [aiRecommendations, setAiRecommendations] = useState([]);
  const [loadingInsights, setLoadingInsights] = useState(false);

  function generateSessionId() {
    return crypto.randomUUID
      ? crypto.randomUUID()
      : Math.random().toString(36).slice(2) + Date.now().toString(36);
  }

  const loadContinuityInsights = useCallback(async ({ silent = false } = {}) => {
    if (!teamCode) return;
    if (!silent) setLoadingInsights(true);

    try {
      const [findingsData, recommendationsData] = await Promise.all([
        getContinuityFindings(teamCode).catch(() => ({ findings: [], count: 0 })),
        getDiagnosticRecommendations(teamCode).catch(() => ({ recommendations: [], count: 0 })),
      ]);

      setAiFindings(findingsData.findings || []);
      setAiRecommendations(recommendationsData.recommendations || []);
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      if (!silent) setLoadingInsights(false);
    }
  }, [teamCode, showToast]);

  useEffect(() => {
    loadContinuityInsights({ silent: true });
  }, [loadContinuityInsights]);

  const resultIssues = useMemo(() => {
    const recommendationByFindingId = new Map(
      aiRecommendations.map((recommendation) => [recommendation.findingId, recommendation])
    );

    return aiFindings.map((finding) => {
      const recommendation = recommendationByFindingId.get(finding.id);
      const goalTag = finding.goalId != null ? `Goal ${finding.goalId}` : 'Team finding';
      const transitionTag = `${finding.docTypeFrom || 'UNKNOWN'} -> ${finding.docTypeTo || 'UNKNOWN'}`;
      return {
        id: `finding-${finding.id}`,
        level: finding.severity || 'MEDIUM',
        confidence: SEVERITY_CONFIDENCE[finding.severity] || SEVERITY_CONFIDENCE.MEDIUM,
        title: `${transitionTag} continuity gap`,
        summary: recommendation?.rootCause || finding.description,
        fix: recommendation?.recommendation,
        tags: [transitionTag, goalTag],
        reported: finding.detectedAt ? new Date(finding.detectedAt).toLocaleDateString() : undefined,
      };
    });
  }, [aiFindings, aiRecommendations]);

  async function handleRunAiAnalysis() {
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
      // Run gap detection
      setAiProgress({ step: 'DETECTING', message: 'Detecting continuity gaps...', percent: 30 });
      const gapsData = await detectContinuityGaps(teamCode, null);
      setAiFindings(gapsData.findings || []);

      // Run recommendation generation
      setAiProgress({ step: 'RECOMMENDING', message: 'Generating diagnostic recommendations...', percent: 60 });
      const recommendationsData = await generateDiagnosticRecommendations(teamCode, 'auto', sessionId);
      setAiRecommendations(recommendationsData.recommendations || []);

      setAiProgress({ step: 'COMPLETE', message: 'AI analysis complete', percent: 100 });
      await reload();
      await loadContinuityInsights({ silent: true });
      
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
    <div className="gtp-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <button type="button" className="gtp-back" onClick={onBack}>
        <ArrowLeft size={14} /> Back to Overview
      </button>

      <PanelHeader
        title={`Team ${teamCode}`}
        subtitle={section || 'Group traceability results'}
        actions={
          <div className="teacher-header-actions">
            <button
              className="btn btn--soft"
              onClick={handleRunAiAnalysis}
              disabled={runningAiAnalysis}
            >
              {runningAiAnalysis ? <Loader2 size={14} className="tm-spin" /> : <Sparkles size={14} />}
              {runningAiAnalysis ? 'Analyzing...' : 'Run AI Analysis'}
            </button>
            <SendButton showToast={showToast} />
            <ExportReportButton showToast={showToast} />
          </div>
        }
      />

      <p className="tm-muted gtp-alignment">
        Overall alignment: <strong>{status === 'ready' ? 'No Gaps Detected' : 'Gap Detected'}</strong>
        {typeof readinessScore === 'number' && <> · Readiness <strong>{readinessScore}%</strong></>}
        {readinessStatus && <> · Backend status <strong>{readinessStatus}</strong></>}
        {typeof findingCount === 'number' && <> · Findings <strong>{findingCount}</strong></>}
        {lastTraceability && <> · Last reviewed {formatDate(lastTraceability)}</>}
      </p>

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

      {loadingInsights && !runningAiAnalysis ? (
        <p className="tm-muted">Loading continuity insights...</p>
      ) : aiFindings.length > 0 || aiRecommendations.length > 0 ? (
        <div className="tp-ai-section" style={{ marginBottom: '1rem' }}>
          <h3 className="tp-section-header">AI-Verified Findings</h3>
          {aiFindings.length > 0 && (
            <div className="tp-ai-findings">
              <h4>Continuity Gaps ({aiFindings.length})</h4>
              <div className="tp-ai-list">
                {aiFindings.map((finding, index) => (
                  <div key={index} className={`tp-ai-item severity-${finding.severity?.toLowerCase() || 'medium'}`}>
                    <div className="tp-ai-severity">{finding.severity || 'MEDIUM'}</div>
                    <div className="tp-ai-description">{finding.description}</div>
                    {finding.detectedAt && (
                      <div className="tp-ai-date">
                        Detected: {new Date(finding.detectedAt).toLocaleString()}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
          {aiRecommendations.length > 0 && (
            <div className="tp-ai-recommendations">
              <h4>Diagnostic Recommendations ({aiRecommendations.length})</h4>
              <div className="tp-ai-list">
                {aiRecommendations.map((rec, index) => (
                  <div key={index} className="tp-ai-item">
                    <div className="tp-ai-description">{rec.recommendation || rec.description}</div>
                    {rec.priority && (
                      <div className="tp-ai-severity">{rec.priority}</div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      ) : null}

      <TraceabilityResults loading={loading || loadingInsights} rows={rows} onComponentClick={setPreviewComponent} issues={resultIssues.length > 0 ? resultIssues : undefined} />

      <ComponentDetailModal
        component={previewComponent}
        onClose={() => setPreviewComponent(null)}
        onRenamed={(updated) => { setPreviewComponent(updated); reload(); }}
        showToast={showToast}
      />
    </div>
  );
}

export default GroupTraceabilityPage;
