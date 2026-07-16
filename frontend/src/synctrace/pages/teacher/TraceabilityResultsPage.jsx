import { useEffect, useMemo, useState } from 'react';
import { Download, Send } from 'lucide-react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { getSmartGoals, getAllGoalComponents } from '../../api';
import { DOC_TYPES } from '../../hooks/useTraceability';
import TraceabilityResults from '../../components/common/TraceabilityResults';
import ComponentDetailModal from '../../components/teacher/ComponentDetailModal';
import SendButton from '../../components/common/SendButton';
import ExportReportButton from '../../components/common/ExportReportButton';
import './TraceabilityResultsPage.css';

const TEAM_CODE = '2026-SEM1-IT01-05';

function TraceabilityResultsPage() {
  const { toast, showToast, hideToast } = useToast();
  const [goals, setGoals] = useState([]);
  const [componentsByGoal, setComponentsByGoal] = useState({});
  const [loading, setLoading] = useState(true);
  const [previewComponent, setPreviewComponent] = useState(null);

  useEffect(() => {
    loadResults();
  }, []);

  async function loadResults() {
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

  const rows = useMemo(() => {
    return goals.map((goal, gi) => {
      const cells = {};
      DOC_TYPES.forEach((dt) => { cells[dt] = componentsByGoal[goal.id]?.filter((c) => c.docType === dt) || []; });
      const coveredTypes = DOC_TYPES.filter((dt) => cells[dt].length > 0).length;
      return {
        goalId: goal.id,
        code: `G${gi + 1}`,
        description: goal.description,
        cells,
        coveredTypes,
        aligned: coveredTypes === DOC_TYPES.length,
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

  return (
    <div className="tp-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Traceability Results"
        subtitle={
          <>
            Traceability coverage across SMART goals and project artifacts
            <span className="tp-team-badge">Team {TEAM_CODE}</span>
          </>
        }
        actions={
          <div className="teacher-header-actions">
            <SendButton showToast={showToast} />
            <ExportReportButton showToast={showToast} />
          </div>
        }
      />

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
