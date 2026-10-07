import { useEffect, useMemo, useState } from 'react';
import AppModal from '../../../../components/common/AppModal';
import { getEvaluationHistory } from '../../../../api';
import { extractSubmissionMeta, formatDateTime } from '../../../../utils/dashboardUtils';

let draftKey = 0;
const nextKey = () => `draft-${++draftKey}`;

/** Server draft → editable rows with stable keys. */
function toEditableDraft(goals) {
  return goals.map((goal) => ({
    key: nextKey(),
    goalKind: goal.goalKind === 'GENERAL' ? 'GENERAL' : 'SPECIFIC',
    description: goal.description || '',
    children: (goal.children || []).map((child) => ({ key: nextKey(), description: child.description || '' })),
  }));
}

/** Editable rows → save payload, dropping anything left blank. */
function toSavePayload(draft) {
  return draft
    .filter((goal) => goal.description.trim())
    .map((goal) => ({
      goalKind: goal.goalKind,
      description: goal.description.trim(),
      children: goal.children
        .filter((child) => child.description.trim())
        .map((child) => ({ goalKind: 'SPECIFIC', description: child.description.trim() })),
    }));
}

function countGoals(payload) {
  return payload.reduce((sum, goal) => sum + 1 + goal.children.length, 0);
}

/**
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
  const payload = reviewing ? toSavePayload(draft) : [];
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
    const goals = await onExtract(doc.fileId, doc.fileName);
    if (!goals) return;
    if (reviewBeforeSave) {
      setDraft(toEditableDraft(goals));
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

  const updateGoal = (key, changes) =>
    setDraft((prev) => prev.map((g) => (g.key === key ? { ...g, ...changes } : g)));
  const removeGoal = (key) => setDraft((prev) => prev.filter((g) => g.key !== key));
  const addGeneral = () =>
    setDraft((prev) => [...prev, { key: nextKey(), goalKind: 'GENERAL', description: '', children: [] }]);
  const updateChild = (goalKey, childKey, description) =>
    setDraft((prev) => prev.map((g) => (g.key !== goalKey ? g : {
      ...g,
      children: g.children.map((c) => (c.key === childKey ? { ...c, description } : c)),
    })));
  const removeChild = (goalKey, childKey) =>
    setDraft((prev) => prev.map((g) => (g.key !== goalKey ? g : {
      ...g,
      children: g.children.filter((c) => c.key !== childKey),
    })));
  const addChild = (goalKey) =>
    setDraft((prev) => prev.map((g) => (g.key !== goalKey ? g : {
      ...g,
      children: [...g.children, { key: nextKey(), description: '' }],
    })));

  function renderSelect() {
    if (extracting) return <p className="tm-muted">Extracting structured SMART goals from the proposal…</p>;
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
              </span>
            </label>
          );
        })}
      </div>
    );
  }

  function renderReview() {
    return (
      <div className="stm-goal-review">
        {draft.length === 0 && (
          <p className="tm-muted">No SMART goals were found. Add them manually below.</p>
        )}
        {draft.map((goal, index) => {
          const isGeneral = goal.goalKind === 'GENERAL';
          return (
            <div key={goal.key} className="stm-goal-review__goal">
              <div className="stm-goal-review__row">
                <span className="tm-goal-kind tm-goal-kind--general">G{index + 1}</span>
                <span className="stm-goal-review__kind">
                  {isGeneral ? 'General objective' : 'Specific objective (no general objective)'}
                </span>
                <button className="stm-mini-btn" onClick={() => removeGoal(goal.key)} disabled={saving}>
                  Remove
                </button>
              </div>
              <textarea
                className="stm-goal-editor__input"
                rows={2}
                value={goal.description}
                placeholder={isGeneral ? 'General objective' : 'Specific objective'}
                disabled={saving}
                onChange={(e) => updateGoal(goal.key, { description: e.target.value })}
              />
              {goal.children.length > 0 && (
                <ul className="stm-goal-review__children">
                  {goal.children.map((child) => (
                    <li key={child.key}>
                      <textarea
                        className="stm-goal-editor__input"
                        rows={2}
                        value={child.description}
                        placeholder="Specific objective"
                        disabled={saving}
                        onChange={(e) => updateChild(goal.key, child.key, e.target.value)}
                      />
                      <button className="stm-mini-btn" onClick={() => removeChild(goal.key, child.key)} disabled={saving}>
                        Remove
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              {isGeneral && (
                <button className="stm-mini-btn stm-goal-cluster__add" onClick={() => addChild(goal.key)} disabled={saving}>
                  Add specific objective
                </button>
              )}
            </div>
          );
        })}
        <button className="btn btn--soft stm-goal-review__add" onClick={addGeneral} disabled={saving}>
          Add general objective
        </button>
      </div>
    );
  }

  return (
    <AppModal
      isOpen={isOpen}
      onClose={handleClose}
      title={reviewing ? 'Review SMART Goals' : 'Extract SMART Goals from Proposal'}
      subtitle={reviewing
        ? 'Edit, remove or add objectives. Nothing is saved until you click Save SMART Goals.'
        : 'Select the evaluated proposal to extract structured General and Specific objectives from.'}
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
