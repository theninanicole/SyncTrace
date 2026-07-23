import { useEffect, useMemo, useState } from 'react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { getSmartGoals, getAllGoalComponents } from '../../api';
import { DOC_TYPES } from '../../hooks/useTraceability';
import { orderGoalsHierarchically } from '../../constants';
import TraceabilityResults from '../../components/common/TraceabilityResults';
import ComponentDetailModal from '../../components/teacher/ComponentDetailModal';
import SendButton from '../../components/common/SendButton';
import ExportReportButton from '../../components/common/ExportReportButton';
import './TraceabilityResultsPage.css';

function TraceabilityResultsPage({ onNavigate }) {
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
        code: `G-${String(gi + 1).padStart(2, '0')}`,
        description: goal.description,
        goalKind: goal.goalKind || 'SPECIFIC',
        parentGoalId: goal.parentGoalId || null,
        parentCode: parentIdx != null ? `G-${String(parentIdx + 1).padStart(2, '0')}` : null,
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

  return (
    <div className="tp-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Results Matrix"
        subtitle="Each row is a SMART goal. Missing cells and the issue list tell you what still needs mapping."
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
        onAddClick={(row, docType) => onNavigate?.('traceability', {
          focusGoalId: row.goalId,
          focusStep: 'map',
          focusDocType: docType,
        })}
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
