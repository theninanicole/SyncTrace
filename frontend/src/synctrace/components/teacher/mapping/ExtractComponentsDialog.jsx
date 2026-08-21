import { useEffect, useMemo, useState } from 'react';
import AppModal from '../../../../components/common/AppModal';
import { getEvaluationHistory } from '../../../../api';
import { extractSubmissionMeta, formatDateTime } from '../../../../utils/dashboardUtils';

function ExtractComponentsDialog({ isOpen, onClose, teamCode, extracting, onExtract }) {
  const [checkedIds, setCheckedIds] = useState(new Set());
  const [historyItems, setHistoryItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(null);

  useEffect(() => {
    if (!isOpen) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCheckedIds(new Set());
    setLoading(true);
    setLoadError(null);
    getEvaluationHistory()
      .then(setHistoryItems)
      .catch((err) => setLoadError(err.message || 'Failed to load evaluated documents.'))
      .finally(() => setLoading(false));
  }, [isOpen, teamCode]);

  const docs = useMemo(() => {
    return historyItems
      .map((item) => ({ ...item, meta: extractSubmissionMeta(item.fileName) }))
      .filter((item) => item.meta.documentType && item.meta.documentType !== 'PROPOSAL'
        && (!teamCode || item.meta.teamCode.toUpperCase() === teamCode.toUpperCase()))
      .sort((a, b) => new Date(b.evaluatedAt) - new Date(a.evaluatedAt));
  }, [historyItems, teamCode]);

  if (!isOpen) return null;

  function toggle(id) {
    setCheckedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function handleExtract() {
    const historyIds = docs.filter((d) => checkedIds.has(d.id)).map((d) => d.id);
    const ok = await onExtract(historyIds);
    if (ok) onClose();
  }

  return (
    <AppModal
      isOpen={isOpen}
      onClose={onClose}
      title="Extract Components"
      subtitle="Select one or more evaluated documents to extract components from."
      footer={
        <div className="modal-actions" style={{ justifyContent: 'space-between', width: '100%' }}>
          <span className="tm-muted">{checkedIds.size} document(s) selected</span>
          <div className="modal-actions">
            <button className="btn" onClick={onClose} disabled={extracting}>Close</button>
            <button className="btn btn--primary" disabled={checkedIds.size === 0 || extracting} onClick={handleExtract}>
              {extracting ? 'Extracting…' : 'Extract Components'}
            </button>
          </div>
        </div>
      }
    >
      {extracting ? (
        <p className="tm-muted">Extracting components from the selected documents…</p>
      ) : loading ? (
        <p className="tm-muted">Loading evaluated documents…</p>
      ) : loadError ? (
        <div className="empty-state">
          <p>{loadError}</p>
        </div>
      ) : docs.length === 0 ? (
        <div className="empty-state">
          <p>{teamCode ? `No evaluated documents found for ${teamCode}.` : 'No evaluated documents found.'}</p>
        </div>
      ) : (
        <div className="tm-team-list">
          {docs.map((doc) => {
            const checked = checkedIds.has(doc.id);
            return (
              <label key={doc.id} className={`tm-team-doc ${checked ? 'tm-team-doc--checked' : ''}`}>
                <input type="checkbox" checked={checked} onChange={() => toggle(doc.id)} />
                <span className="tm-badge" data-doctype={doc.meta.documentType}>{doc.meta.documentType}</span>
                <span className="tm-team-doc__meta">
                  {doc.meta.teamCode || 'Unassigned'} · v{doc.version} · {formatDateTime(doc.evaluatedAt)}
                </span>
              </label>
            );
          })}
        </div>
      )}
    </AppModal>
  );
}

export default ExtractComponentsDialog;
