import { useEffect, useMemo, useState } from 'react';
import { Download, RefreshCcw } from 'lucide-react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { getSmartGoals, getAllGoalComponents } from '../../api';
import './MatrixPage.css';

function MatrixPage() {
  const { toast, showToast, hideToast } = useToast();
  const [goals, setGoals] = useState([]);
  const [componentsByGoal, setComponentsByGoal] = useState({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadMatrix();
  }, []);

  async function loadMatrix() {
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

  const DOC_TYPES = ['SRS', 'SDD', 'SPMP', 'STD', 'IMPLEMENTATION'];

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
    <div className="mp-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Matrix"
        subtitle="Traceability coverage across SMART goals and project artifacts"
        actions={
          <div className="teacher-header-actions">
            <button type="button" className="btn btn--soft" onClick={loadMatrix} disabled={loading}>
              <RefreshCcw size={14} /> Refresh
            </button>
            <button type="button" className="btn btn--soft" onClick={() => showToast('Export coming soon', 'info')}>
              <Download size={14} /> Export report
            </button>
          </div>
        }
      />

      <div className="mp-kpi-row">
        <div className="mp-kpi mp-kpi--ok">
          <div className="mp-kpi__value">{metrics.alignmentPercent}%</div>
          <div className="mp-kpi__label">Alignment</div>
          <div className="mp-kpi__subtitle">Coverage across goals</div>
        </div>
        <div className="mp-kpi">
          <div className="mp-kpi__value">{metrics.unmappedGoals}</div>
          <div className="mp-kpi__label">Unmapped Goals</div>
          <div className="mp-kpi__subtitle">No traceability found</div>
        </div>
        <div className="mp-kpi">
          <div className="mp-kpi__value">{metrics.partialGoals}</div>
          <div className="mp-kpi__label">Partial Goals</div>
          <div className="mp-kpi__subtitle">Some document types mapped</div>
        </div>
        <div className="mp-kpi">
          <div className="mp-kpi__value">{metrics.missingCells}</div>
          <div className="mp-kpi__label">Missing Cells</div>
          <div className="mp-kpi__subtitle">Required traceability gaps</div>
        </div>
        <div className="mp-kpi">
          <div className="mp-kpi__value">{metrics.fullyCoveredGoals}</div>
          <div className="mp-kpi__label">Fully Covered</div>
          <div className="mp-kpi__subtitle">Goals with all doc types</div>
        </div>
      </div>

      <div className="mp-summary">
        <div className="mp-summary__text">{metrics.missingCells} traceability gap{metrics.missingCells !== 1 ? 's' : ''} remain across {rows.length} goal{rows.length !== 1 ? 's' : ''}.</div>
        <div className="mp-summary__actions" />
      </div>

      <div className="mp-table-card">
        <div className="mp-table-header">
          <div className="mp-col mp-col--goal">SMART Goal</div>
          <div className="mp-col">SRS</div>
          <div className="mp-col">SDD</div>
          <div className="mp-col">SPMP</div>
          <div className="mp-col">STD</div>
          <div className="mp-col">Implementation</div>
          <div className="mp-col mp-col--status">Status</div>
        </div>

        <div className="mp-table-body">
          {rows.map((row) => (
            <div className="mp-table-row" key={row.goalId}>
              <div className="mp-col mp-col--goal">
                <div className="mp-goal-id">{row.code}</div>
                <div className="mp-goal-title">{row.description}</div>
              </div>
              <div className="mp-col">{(row.cells.SRS || []).length > 0 ? (row.cells.SRS[0].name) : <span className="mp-status mp-status--missing">Missing</span>}</div>
              <div className="mp-col">{(row.cells.SDD || []).length > 0 ? (row.cells.SDD[0].name) : <span className="mp-status mp-status--missing">Missing</span>}</div>
              <div className="mp-col">{(row.cells.SPMP || []).length > 0 ? (row.cells.SPMP[0].name) : <span className="mp-status mp-status--missing">Missing</span>}</div>
              <div className="mp-col">{(row.cells.STD || []).length > 0 ? (row.cells.STD[0].name) : <span className="mp-status mp-status--missing">Missing</span>}</div>
              <div className="mp-col">{(row.cells.IMPLEMENTATION || []).length > 0 ? (row.cells.IMPLEMENTATION[0].name) : <span className="mp-status mp-status--missing">Missing</span>}</div>
              <div className="mp-col mp-col--status">{row.aligned ? <span className="mp-badge mp-badge--ok">Passed</span> : <span className="mp-badge mp-badge--warn">Failed</span>}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export default MatrixPage;
