import { useEffect, useMemo, useState } from 'react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { getSmartGoals, getAllGoalComponents, detectContinuityGaps, generateDiagnosticRecommendations } from '../../api';
import { API_BASE_URL } from '../../../api';
import { fetchClassRoster, fetchTeacherHistory } from '../../../services/dashboardService';
import { extractSubmissionMeta } from '../../../utils/dashboardUtils';
import { DOC_TYPES } from '../../hooks/useTraceability';
import { useSelectedTeam } from '../../hooks/useSelectedTeam';
import { orderGoalsHierarchically } from '../../constants';
import { buildAiIssues } from '../../utils/gapIssues';
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
  const [goals, setGoals] = useState([]);
  const [componentsByGoal, setComponentsByGoal] = useState({});
  const [roster, setRoster] = useState([]);
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);
  const [previewComponent, setPreviewComponent] = useState(null);

  const [runningAiAnalysis, setRunningAiAnalysis] = useState(false);
  const [aiProgress, setAiProgress] = useState({ step: '', message: '', percent: 0 });
  const [aiFindings, setAiFindings] = useState([]);
  const [aiRecommendations, setAiRecommendations] = useState([]);

  useEffect(() => {
    loadResults();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedTeam]);

  async function loadResults() {
    setLoading(true);
    try {
      const [goalList, allGoalComponents, rosterList, historyList] = await Promise.all([
        getSmartGoals(selectedTeam || undefined),
        getAllGoalComponents(),
        fetchClassRoster().catch(() => []),
        fetchTeacherHistory().catch(() => []),
      ]);
      setGoals(goalList);
      setComponentsByGoal(allGoalComponents);
      setRoster(rosterList);
      setHistory(historyList);
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoading(false);
    }
  }

  const rows = useMemo(() => {
    const ordered = orderGoalsHierarchically(goals);
    const indexById = new Map(ordered.map((g, i) => [g.id, i]));
    return ordered.map((goal, gi) => {
      const mapped = componentsByGoal[goal.id] || componentsByGoal[String(goal.id)] || [];
      const cells = {};
      DOC_TYPES.forEach((dt) => { cells[dt] = mapped.filter((c) => c.docType === dt); });
      const coveredTypes = DOC_TYPES.filter((dt) => cells[dt].length > 0).length;
      const parentIdx = goal.parentGoalId != null ? indexById.get(goal.parentGoalId) : null;
      return {
        goalId: goal.id,
        code: `G${gi + 1}`,
        description: goal.description,
        goalKind: goal.goalKind || 'SPECIFIC',
        parentGoalId: goal.parentGoalId || null,
        parentCode: parentIdx != null ? `G${parentIdx + 1}` : null,
        teamCode: goal.teamCode || '',
        cells,
        coveredTypes,
        aligned: coveredTypes === DOC_TYPES.length,
        createdAt: goal.createdAt,
        nested: goal.goalKind !== 'GENERAL' && Boolean(goal.parentGoalId),
      };
    });
  }, [goals, componentsByGoal]);

  const metrics = useMemo(() => {
    let missingCells = 0;
    let unmappedGoals = 0;
    let partialGoals = 0;
    let fullyCoveredGoals = 0;

    rows.forEach((r) => {
      DOC_TYPES.forEach((dt) => {
        if (!r.cells[dt] || r.cells[dt].length === 0) missingCells++;
      });

      if (r.coveredTypes === 0) unmappedGoals++;
      else if (r.coveredTypes < DOC_TYPES.length) partialGoals++;
      else fullyCoveredGoals++;
    });

    const alignmentPercent = rows.length === 0 ? 0 : Math.round((1 - (missingCells / (rows.length * DOC_TYPES.length))) * 100);

    return {
      alignmentPercent,
      unmappedGoals,
      partialGoals,
      missingCells,
      fullyCoveredGoals,
    };
  }, [rows]);

  // goal.teamCode is often blank (only set when the proposal-analysis payload supplied
  // one), so resolve team codes the same way the Overview page does: the class roster
  // plus whatever team each mapped component's source submission belonged to.
  const teamCodes = useMemo(() => {
    const historyTeamMap = new Map();
    history.forEach((h) => {
      const meta = extractSubmissionMeta(h.fileName);
      if (meta.teamCode) historyTeamMap.set(h.id, meta.teamCode);
    });

    const displayCodeByKey = new Map();
    const addCode = (code) => {
      if (!code) return;
      const key = code.toUpperCase();
      if (!displayCodeByKey.has(key)) displayCodeByKey.set(key, code);
    };

    roster.forEach((s) => addCode(s.groupCode));
    Object.values(componentsByGoal).flat().forEach((c) => {
      addCode(c.sourceHistoryId != null ? historyTeamMap.get(c.sourceHistoryId) : null);
    });
    rows.forEach((r) => addCode(r.teamCode));

    return Array.from(displayCodeByKey.values());
  }, [roster, history, componentsByGoal, rows]);

  const goalCodeById = useMemo(
    () => new Map(rows.map((r) => [r.goalId, r.code])),
    [rows],
  );

  const aiIssues = useMemo(
    () => buildAiIssues(aiFindings, aiRecommendations, goalCodeById),
    [aiFindings, aiRecommendations, goalCodeById],
  );

  function generateSessionId() {
    return crypto.randomUUID
      ? crypto.randomUUID()
      : Math.random().toString(36).slice(2) + Date.now().toString(36);
  }

  async function handleRunAiAnalysis() {
    if (teamCodes.length === 0) {
      showToast('No teams with goals to analyze.', 'error');
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
      // detectContinuityGaps analyzes goals across the whole matrix regardless of which
      // teamCode is passed (the backend doesn't scope the scan by team), so a single call
      // covers every goal shown on this page. The teamCode only tags the findings it
      // creates and scopes which of those the recommendations call can see, so reuse the
      // same code for both calls rather than looping per team (which would just create
      // duplicate findings, one set per team).
      const team = teamCodes[0];

      setAiProgress({ step: 'DETECTING', message: 'Detecting continuity gaps...', percent: 30 });
      const gapsData = await detectContinuityGaps(team, null);
      setAiFindings(gapsData.findings || []);

      setAiProgress({ step: 'RECOMMENDING', message: 'Generating diagnostic recommendations...', percent: 60 });
      const recommendationsData = await generateDiagnosticRecommendations(team, 'auto', sessionId);
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
            <SendButton showToast={showToast} />
            <ExportReportButton showToast={showToast} />
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
