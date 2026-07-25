import { useEffect, useMemo, useState } from 'react';
import { CircleCheck, CircleX, FileText, Loader2, Users } from 'lucide-react';
import AppModal from '../../../components/common/AppModal';
import { extractSubmissionMeta, formatDateTime } from '../../../utils/dashboardUtils';
import { getEvaluationHistory } from '../../../api';
import { extractTraceComponents } from '../../api';

function ExtractComponentsModal({ isOpen, onClose, showToast, onExtracted, teamCode = '' }) {
  const [historyItems, setHistoryItems] = useState([]);
  const [loading, setLoading]           = useState(false);
  const [selectedIds, setSelectedIds]   = useState(new Set());
  const [processingId, setProcessingId] = useState(null);
  const [isExtracting, setIsExtracting] = useState(false);
  const [results, setResults]           = useState(new Map());

  useEffect(() => {
    if (!isOpen) return;
    setLoading(true);
    getEvaluationHistory()
      .then(setHistoryItems)
      .catch((err) => showToast?.(err.message, 'error'))
      .finally(() => setLoading(false));
    setSelectedIds(new Set());
    setResults(new Map());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, teamCode]);

  // PROPOSAL documents are only used for goal extraction (see ExtractGoalsModal),
  // not component extraction, so they never belong in this list.
  const teams = useMemo(() => {
    const byTeam = new Map();
    historyItems.forEach((item) => {
      const meta = extractSubmissionMeta(item.fileName);
      if (meta.documentType === 'PROPOSAL') return;
      const itemTeamCode = meta.teamCode || 'Unassigned';
      if (!byTeam.has(itemTeamCode)) byTeam.set(itemTeamCode, []);
      byTeam.get(itemTeamCode).push({ ...item, meta });
    });

    return [...byTeam.entries()]
      .map(([code, docs]) => ({
        teamCode: code,
        docs: docs.sort((a, b) => a.meta.documentType.localeCompare(b.meta.documentType)),
      }))
      .filter(({ teamCode: code }) => !teamCode || code.toUpperCase() === teamCode.toUpperCase())
      .sort((a, b) => a.teamCode.localeCompare(b.teamCode));
  }, [historyItems, teamCode]);

  if (!isOpen) return null;

  function toggleSelected(historyId) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(historyId)) next.delete(historyId); else next.add(historyId);
      return next;
    });
  }

  async function handleExtractSelected() {
    const ids = Array.from(selectedIds);
    if (ids.length === 0) return;

    setIsExtracting(true);
    const nextResults = new Map(results);
    let totalFound = 0;
    let failed = 0;

    for (const historyId of ids) {
      setProcessingId(historyId);
      try {
        const result = await extractTraceComponents(historyId);
        nextResults.set(historyId, { status: 'success', count: result.count });
        totalFound += result.count;
      } catch (err) {
        nextResults.set(historyId, { status: 'error', message: err.message });
        failed += 1;
      }
      setResults(new Map(nextResults));
    }

    setProcessingId(null);
    setIsExtracting(false);
    setSelectedIds(new Set());
    onExtracted?.();

    if (failed === 0) {
      showToast?.(`Found ${totalFound} component(s) across ${ids.length} document(s) — now available in the library.`, 'success');
    } else {
      showToast?.(`${ids.length - failed} of ${ids.length} document(s) extracted (${totalFound} component(s) found). ${failed} failed — see the error under each document in this list.`, 'error');
    }
  }

  return (
    <AppModal
      isOpen={isOpen}
      onClose={onClose}
      title="Extract Components"
      subtitle="Pick a team and select submitted documents to pull components from."
      footer={
        <div className="modal-actions" style={{ justifyContent: 'space-between', width: '100%' }}>
          <span className="tm-muted">{selectedIds.size} document(s) selected</span>
          <div className="modal-actions">
            <button className="btn" onClick={onClose} disabled={isExtracting}>Close</button>
            <button
              className="btn btn--primary"
              disabled={selectedIds.size === 0 || isExtracting}
              onClick={handleExtractSelected}
            >
              {isExtracting ? 'Extracting...' : 'Extract'}
            </button>
          </div>
        </div>
      }
    >
      {loading ? (
        <p className="tm-muted">Loading submissions...</p>
      ) : teams.length === 0 ? (
        <div className="empty-state">
          <p>{teamCode ? `No evaluated submissions found for ${teamCode}.` : 'No evaluated submissions yet.'}</p>
        </div>
      ) : (
        <div className="tm-team-list">
          {teams.map(({ teamCode, docs }) => (
            <div key={teamCode} className="tm-team-group">
              <div className="tm-team-group__header tm-team-group__header--static">
                <Users size={14} />
                <span className="tm-team-group__code">{teamCode}</span>
              </div>

              <div className="tm-team-group__docs">
                {docs.map((doc) => {
                  const checked = selectedIds.has(doc.id);
                  const isProcessing = processingId === doc.id;
                  const result = results.get(doc.id);
                  return (
                    <div key={doc.id} className="tm-team-doc-block">
                      <label
                        className={`tm-team-doc ${checked ? 'tm-team-doc--checked' : ''}`}
                      >
                        <input
                          type="checkbox"
                          checked={checked}
                          disabled={isExtracting}
                          onChange={() => toggleSelected(doc.id)}
                        />
                        <FileText size={14} />
                        <span className="tm-badge" data-doctype={doc.meta.documentType}>
                          {doc.meta.documentType || '—'}
                        </span>
                        <span className="tm-team-doc__meta">
                          v{doc.version} · {formatDateTime(doc.evaluatedAt)}
                        </span>
                        {isProcessing && <Loader2 size={14} className="tm-spin" />}
                        {!isProcessing && result?.status === 'success' && (
                          <span className="tm-team-doc__result tm-team-doc__result--ok">
                            <CircleCheck size={14} />
                          </span>
                        )}
                        {!isProcessing && result?.status === 'error' && (
                          <span className="tm-team-doc__result tm-team-doc__result--error">
                            <CircleX size={14} />
                          </span>
                        )}
                      </label>
                      {!isProcessing && result?.status === 'success' && (
                        <p className="tm-team-doc__detail tm-team-doc__detail--ok">
                          Extracted {result.count} component(s) into the library.
                        </p>
                      )}
                      {!isProcessing && result?.status === 'error' && (
                        <p className="tm-team-doc__detail tm-team-doc__detail--error">
                          {result.message || 'Extraction failed for this document.'}
                        </p>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      )}
    </AppModal>
  );
}

export default ExtractComponentsModal;
