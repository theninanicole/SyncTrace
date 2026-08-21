import { useEffect, useMemo, useState } from 'react';
import AppModal from '../../../../components/common/AppModal';
import { getEvaluationHistory } from '../../../../api';
import { extractSubmissionMeta, formatDateTime } from '../../../../utils/dashboardUtils';

function ExtractSmartGoalsDialog({ isOpen, onClose, teamCode, extracting, onExtract }) {
  const [selectedId, setSelectedId] = useState(null);
  const [historyItems, setHistoryItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(null);

  useEffect(() => {
    if (!isOpen) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelectedId(null);
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

  async function handleExtract() {
    const doc = proposals.find((d) => d.id === selectedId);
    if (!doc) return;
    const ok = await onExtract(doc.fileId, doc.fileName);
    if (ok) onClose();
  }

  return (
    <AppModal
      isOpen={isOpen}
      onClose={onClose}
      title="Extract SMART Goals from Proposal"
      subtitle="Select the evaluated proposal to extract structured General and Specific objectives from."
      footer={
        <div className="modal-actions" style={{ justifyContent: 'space-between', width: '100%' }}>
          <span className="tm-muted">{selectedId ? '1 document selected' : 'No document selected'}</span>
          <div className="modal-actions">
            <button className="btn" onClick={onClose} disabled={extracting}>Close</button>
            <button className="btn btn--primary" disabled={!selectedId || extracting} onClick={handleExtract}>
              {extracting ? 'Extracting…' : 'Extract Goals'}
            </button>
          </div>
        </div>
      }
    >
      {extracting ? (
        <p className="tm-muted">Extracting structured SMART goals from the proposal…</p>
      ) : loading ? (
        <p className="tm-muted">Loading evaluated documents…</p>
      ) : loadError ? (
        <div className="empty-state">
          <p>{loadError}</p>
        </div>
      ) : proposals.length === 0 ? (
        <div className="empty-state">
          <p>{teamCode ? `No evaluated proposal found for ${teamCode}.` : 'No evaluated proposal found.'}</p>
        </div>
      ) : (
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
      )}
    </AppModal>
  );
}

export default ExtractSmartGoalsDialog;
