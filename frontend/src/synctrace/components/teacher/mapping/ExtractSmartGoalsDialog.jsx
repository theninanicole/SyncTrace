import { useEffect, useMemo, useState } from 'react';
import AppModal from '../../../../components/common/AppModal';
import { getEvaluationHistory } from '../../../../api';
import { extractSubmissionMeta, formatDateTime } from '../../../../utils/dashboardUtils';
import { readSmartGoals, toEditableGoalDraft, toGoalPayload } from '../../../../utils/smartGoalsSection';
import SmartGoalTreeEditor from '../../common/SmartGoalTreeEditor';

function countGoals(payload) {
  return payload.reduce((sum, goal) => sum + 1 + goal.children.length, 0);
}

/**
 * Goals come from the SMART Goals section of the selected proposal's evaluation report. A
 * report without that section (evaluated before the section existed) falls back to AI
 * extraction from the proposal document.
 * reviewBeforeSave (teacher side): extracted goals open in an editable review step and are
 * only saved on Save SMART Goals. Without it (student side) they are saved as extracted.
 */
function ExtractSmartGoalsDialog({ isOpen, onClose, teamCode, extracting, onExtract, onSave, reviewBeforeSave = false }) {
  const [selectedId, setSelectedId] = useState(null);
  const [historyItems, setHistoryItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(null);
  // null while choosing a proposal; the editable goal tree once extraction returns.
  const [draft, setDraft] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelectedId(null);
    setDraft(null);
    setLoading(true);
    setLoadError(null);
    getEvaluationHistory()
      .then(setHistoryItems)
      .catch((err) => setLoadError(err.message || 'Failed to load evaluated documents.'))
      .finally(() => setLoading(false));
  }, [isOpen, teamCode]);

  const proposals = useMemo(() => {
    return historyItems
      .map((item) => ({ ...item, meta: extractSubmissionMeta(item.fileName) }))
      .filter((item) => item.meta.documentType === 'PROPOSAL'
        && (!teamCode || item.meta.teamCode.toUpperCase() === teamCode.toUpperCase()))
      .sort((a, b) => new Date(b.evaluatedAt) - new Date(a.evaluatedAt));
  }, [historyItems, teamCode]);

  if (!isOpen) return null;

  const reviewing = draft !== null;
  const payload = reviewing ? toGoalPayload(draft) : [];
  const busy = extracting || saving;

  // Closing during review would silently drop the edits, so ask first.
  function handleClose() {
    if (busy) return;
    if (reviewing && !window.confirm('Discard these SMART goals? Nothing has been saved yet.')) return;
    onClose();
  }

  async function handleExtract() {
    const doc = proposals.find((d) => d.id === selectedId);
    if (!doc) return;
    const goals = await onExtract(doc.fileId, doc.fileName, doc.evaluationResult);
    if (!goals) return;
    if (reviewBeforeSave) {
      setDraft(toEditableGoalDraft(goals));
      return;
    }
    if (goals.length === 0) return;
    setSaving(true);
    const ok = await onSave(goals);
    setSaving(false);
    if (ok) onClose();
  }

  async function handleSave() {
    if (payload.length === 0) return;
    setSaving(true);
    const ok = await onSave(payload);
    setSaving(false);
    if (ok) onClose();
  }

  function renderSelect() {
    if (extracting) return <p className="tm-muted">Extracting structured SMART goals from the proposal document…</p>;
    if (loading) return <p className="tm-muted">Loading evaluated documents…</p>;
    if (loadError) return <div className="empty-state"><p>{loadError}</p></div>;
    if (proposals.length === 0) {
      return (
        <div className="empty-state">
          <p>{teamCode ? `No evaluated proposal found for ${teamCode}.` : 'No evaluated proposal found.'}</p>
        </div>
      );
    }
    return (
      <div className="tm-team-list">
        {proposals.map((doc) => {
          const checked = selectedId === doc.id;
          const hasGoalsSection = readSmartGoals(doc.evaluationResult).found;
          return (
            <label key={doc.id} className={`tm-team-doc ${checked ? 'tm-team-doc--checked' : ''}`}>
              <input
                type="radio"
                name="proposal-selection"
                checked={checked}
                onChange={() => setSelectedId(doc.id)}
              />
              <span className="tm-badge" data-doctype="PROPOSAL">PROPOSAL</span>
              <span className="tm-team-doc__meta">
                {doc.meta.teamCode || 'Unassigned'} · v{doc.version} · {formatDateTime(doc.evaluatedAt)}
                {!hasGoalsSection && ' · no SMART Goals section in this report — goals will be extracted from the document by AI'}
              </span>
            </label>
          );
        })}
      </div>
    );
  }

  function renderReview() {
    return (
      <SmartGoalTreeEditor
        draft={draft}
        onChange={setDraft}
        disabled={saving}
        emptyMessage="No SMART goals were found. Add them manually below."
      />
    );
  }

  return (
    <AppModal
      isOpen={isOpen}
      onClose={handleClose}
      title={reviewing ? 'Review SMART Goals' : 'Extract SMART Goals from Proposal'}
      subtitle={reviewing
        ? 'Edit, remove or add objectives. Nothing is saved until you click Save SMART Goals.'
        : 'Select the evaluated proposal. Its General and Specific objectives are taken from the SMART Goals section of the evaluation report.'}
      footer={reviewing ? (
        <div className="modal-actions" style={{ justifyContent: 'space-between', width: '100%' }}>
          <span className="tm-muted">{countGoals(payload)} objective(s) to save</span>
          <div className="modal-actions">
            <button className="btn" onClick={() => setDraft(null)} disabled={saving}>Back</button>
            <button className="btn" onClick={onClose} disabled={saving}>Discard</button>
            <button className="btn btn--primary" disabled={payload.length === 0 || saving} onClick={handleSave}>
              {saving ? 'Saving…' : 'Save SMART Goals'}
            </button>
          </div>
        </div>
      ) : (
        <div className="modal-actions" style={{ justifyContent: 'space-between', width: '100%' }}>
          <span className="tm-muted">{selectedId ? '1 document selected' : 'No document selected'}</span>
          <div className="modal-actions">
            <button className="btn" onClick={onClose} disabled={busy}>Close</button>
            <button className="btn btn--primary" disabled={!selectedId || busy} onClick={handleExtract}>
              {extracting ? 'Extracting…' : saving ? 'Saving…' : 'Extract Goals'}
            </button>
          </div>
        </div>
      )}
    >
      {reviewing ? renderReview() : renderSelect()}
    </AppModal>
  );
}

export default ExtractSmartGoalsDialog;
