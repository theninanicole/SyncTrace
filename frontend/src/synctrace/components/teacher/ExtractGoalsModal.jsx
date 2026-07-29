import { useEffect, useMemo, useState } from 'react';
import { CircleCheck, CircleX, FileText, Loader2, Users, Sparkles } from 'lucide-react';
import AppModal from '../../../components/common/AppModal';
import { extractSubmissionMeta, formatDateTime } from '../../../utils/dashboardUtils';
import { getEvaluationHistory } from '../../../api';
import { extractSmartGoalsFromProposal } from '../../api';
import { API_BASE_URL } from '../../../api';

function generateSessionId() {
  return crypto.randomUUID
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function ExtractGoalsModal({ isOpen, onClose, showToast, onExtracted, teamCode = '' }) {
  const [historyItems, setHistoryItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [selectedId, setSelectedId] = useState(null);
  const [isExtracting, setIsExtracting] = useState(false);
  const [progress, setProgress] = useState({ step: '', message: '', percent: 0 });
  const [result, setResult] = useState(null);

  useEffect(() => {
    if (!isOpen) return;
    setLoading(true);
    getEvaluationHistory()
      .then(setHistoryItems)
      .catch((err) => showToast?.(err.message, 'error'))
      .finally(() => setLoading(false));
    setSelectedId(null);
    setResult(null);
    setProgress({ step: '', message: '', percent: 0 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, teamCode]);

  const teams = useMemo(() => {
    const byTeam = new Map();
    historyItems.forEach((item) => {
      const meta = extractSubmissionMeta(item.fileName);
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

  // Filter for proposal documents only
  const proposalDocs = useMemo(() => {
    return teams.map(({ teamCode: code, docs }) => ({
      teamCode: code,
      docs: docs.filter(doc => doc.meta.documentType === 'PROPOSAL'),
    })).filter(({ docs }) => docs.length > 0);
  }, [teams]);

  if (!isOpen) return null;

  async function handleExtract() {
    if (!selectedId) return;

    const sessionId = generateSessionId();
    setIsExtracting(true);
    setProgress({ step: 'RECEIVED', message: 'Starting proposal analysis...', percent: 0 });
    setResult(null);

    const es = new EventSource(`${API_BASE_URL}/ai/progress/${sessionId}`);

    es.addEventListener('progress', (e) => {
      try {
        const { step, message, percent } = JSON.parse(e.data);
        setProgress({ step, message, percent });
      } catch { /* ignore parse errors */ }
    });

    es.addEventListener('done', () => {
      es.close();
    });

    es.addEventListener('error', (e) => {
      try {
        const { error: msg } = JSON.parse(e.data || '{}');
        if (msg) showToast(`Extraction error: ${msg}`, 'error');
      } catch { /* ignore */ }
      es.close();
    });

    es.onerror = () => {
      es.close();
    };

    try {
      const selectedDoc = historyItems.find(item => item.id === selectedId);
      const meta = extractSubmissionMeta(selectedDoc?.fileName);
      const teamCode = meta.teamCode && meta.teamCode !== 'Unassigned' ? meta.teamCode : null;
      const data = await extractSmartGoalsFromProposal(
        selectedDoc.fileId,
        selectedDoc.fileName,
        'auto',
        sessionId,
        teamCode
      );
      
      setResult(data);
      
      if (data.count === 0) {
        showToast('No SMART goals found in this proposal. The document may be empty or the AI could not extract goals.', 'info');
      } else {
        showToast(`Successfully extracted ${data.count} SMART goal(s) from the proposal.`, 'success');
      }
      
      onExtracted?.();
    } catch (err) {
      showToast(err.message, 'error');
      setResult({ error: err.message });
    } finally {
      setIsExtracting(false);
      es.close();
    }
  }

  return (
    <AppModal
      isOpen={isOpen}
      onClose={onClose}
      title="Extract SMART Goals from Proposal"
      subtitle="Select a proposal document to extract its SMART goals."
      footer={
        <div className="modal-actions" style={{ justifyContent: 'space-between', width: '100%' }}>
          <span className="tm-muted">
            {selectedId ? '1 document selected' : 'No document selected'}
          </span>
          <div className="modal-actions">
            <button className="btn" onClick={onClose} disabled={isExtracting}>Close</button>
            <button
              className="btn btn--primary"
              disabled={!selectedId || isExtracting}
              onClick={handleExtract}
            >
              {isExtracting ? 'Extracting...' : 'Extract Goals'}
            </button>
          </div>
        </div>
      }
    >
      {loading ? (
        <p className="tm-muted">Loading submissions...</p>
      ) : proposalDocs.length === 0 ? (
        <div className="empty-state">
          <p>{teamCode ? `No proposal document found for ${teamCode}.` : 'No proposal document found in evaluation history.'}</p>
        </div>
      ) : (
        <>
          {isExtracting && (
            <div className="sc-progress" style={{ marginBottom: '1rem' }}>
              <div className="sc-progress-bar">
                <div className="sc-progress-fill" style={{ width: `${progress.percent}%` }} />
              </div>
              <div className="sc-progress-text">
                {progress.step}: {progress.message} ({progress.percent}%)
              </div>
            </div>
          )}

          {result && !result.error && (
            <div className="tm-result-summary" style={{ marginBottom: '1rem', padding: '0.75rem', background: '#f0fdf4', borderRadius: '4px', border: '1px solid #86efac' }}>
              <span className="tm-team-doc__result tm-team-doc__result--ok" style={{ marginRight: '0.5rem' }}>
                <CircleCheck size={16} />
              </span>
              <span>Extracted {result.count} SMART goal(s)</span>
            </div>
          )}

          {result && result.error && (
            <div className="tm-result-summary" style={{ marginBottom: '1rem', padding: '0.75rem', background: '#fef2f2', borderRadius: '4px', border: '1px solid #fca5a5' }}>
              <span className="tm-team-doc__result tm-team-doc__result--error" style={{ marginRight: '0.5rem' }}>
                <CircleX size={16} />
              </span>
              <span>{result.error}</span>
            </div>
          )}

          <div className="tm-team-list">
            {proposalDocs.map(({ teamCode, docs }) => (
              <div key={teamCode} className="tm-team-group">
                <div className="tm-team-group__header tm-team-group__header--static">
                  <Users size={14} />
                  <span className="tm-team-group__code">{teamCode}</span>
                </div>

                <div className="tm-team-group__docs">
                  {docs.map((doc) => {
                    const checked = selectedId === doc.id;
                    return (
                      <label
                        key={doc.id}
                        className={`tm-team-doc ${checked ? 'tm-team-doc--checked' : ''}`}
                      >
                        <input
                          type="radio"
                          name="proposal-selection"
                          checked={checked}
                          disabled={isExtracting}
                          onChange={() => setSelectedId(doc.id)}
                        />
                        <FileText size={14} />
                        <span className="tm-badge" data-doctype={doc.meta.documentType}>
                          {doc.meta.documentType || '—'}
                        </span>
                        <span className="tm-team-doc__meta">
                          v{doc.version} · {formatDateTime(doc.evaluatedAt)}
                        </span>
                      </label>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </AppModal>
  );
}

export default ExtractGoalsModal;
