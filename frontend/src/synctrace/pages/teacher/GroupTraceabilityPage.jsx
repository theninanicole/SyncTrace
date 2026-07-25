import { useState } from 'react';
import { ArrowLeft } from 'lucide-react';
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
import './TraceabilityMappingPage.css';
import './GroupTraceabilityPage.css';

const SEVERITY_CONFIDENCE = {
  CRITICAL: 98,
  HIGH: 90,
  MEDIUM: 75,
  LOW: 60,
};

function GroupTraceabilityPage({ teamCode, onBack, onNavigate }) {
  const { toast, showToast, hideToast } = useToast();
  const { loading, section, rows, status, lastTraceability, readinessScore, readinessStatus, findingCount, aiIssues, reload } = useGroupTraceability(teamCode, showToast);
  const [previewComponent, setPreviewComponent] = useState(null);
  //eslint-disable-next-line no-unused-vars
  const meta = STATUS_META[status];

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
            <SendButton showToast={showToast} />
            <ExportReportButton showToast={showToast} teamCode={teamCode} />
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
        issuesEmptyMessage="Run AI Analysis from Traceability Results to detect continuity gaps for this team."
      />

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
