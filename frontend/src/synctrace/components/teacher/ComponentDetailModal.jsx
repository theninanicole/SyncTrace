import { useEffect, useState } from 'react';
import { Check, Loader2, Pencil, X } from 'lucide-react';
import AppModal from '../../../components/common/AppModal';
import { renameTraceComponent, getTraceComponent } from '../../api';
import { componentLabel } from '../../constants';
import { formatDate } from '../../../utils/dashboardUtils';

function isImgHeaderLine(line) {
  return /^\*?\s*\[IMG-\d+\]/i.test(line);
}

function cleanContentLine(line) {
  return line.replace(/^[-*]\s+/, '');
}

function extractSourceMeta(content) {
  if (!content) return { filePath: null, source: '' };
  const match = content.match(/^File:\s*(.+?)\r?\n\r?\n([\s\S]*)$/);
  if (match) {
    return { filePath: match[1].trim(), source: match[2] };
  }
  return { filePath: null, source: content };
}

/** Describes where a component's content came from, for display in the detail modal. */
function describeProvenance(component) {
  const capturedAt = component.sourceCapturedAt || component.createdAt;
  const dateLabel = capturedAt ? formatDate(capturedAt) : null;

  if (component.sourceType === 'GITHUB') {
    return { label: component.sourceRef ? `GitHub \u2014 ${component.sourceRef}` : 'Ingested from GitHub', url: component.sourceUrl, dateLabel };
  }
  if (component.sourceType === 'EVALUATION_HISTORY') {
    return { label: 'AI-extracted from an evaluated submission', url: null, dateLabel };
  }
  if (component.sourceType === 'MANUAL') {
    return { label: 'Manually added', url: null, dateLabel };
  }
  // Backward compatibility for components created before source tracking was added.
  if (component.aiExtracted) {
    return {
      label: component.docType === 'IMPLEMENTATION'
        ? 'Ingested from the team\'s GitHub repository.'
        : 'Extracted from an evaluated submission.',
      url: null,
      dateLabel,
    };
  }
  return { label: 'Manually added', url: null, dateLabel };
}

function ComponentDetailModal({ component, onClose, onRenamed, showToast, readOnly = false }) {
  const [isEditing, setIsEditing]     = useState(false);
  const [draftName, setDraftName]     = useState('');
  const [saving, setSaving]           = useState(false);
  const [imageExpanded, setImageExpanded] = useState(false);
  // List views omit content/imageData to keep them lightweight — fetch the full
  // component (with image/source) only when it's actually opened here.
  const [detail, setDetail]           = useState(null);
  const [loadError, setLoadError]     = useState('');

  useEffect(() => {
    setIsEditing(false);
    setDraftName(component?.codeName || component?.name || '');
    setImageExpanded(false);
    setDetail(null);
    setLoadError('');

    if (!component?.id) return;
    let cancelled = false;
    getTraceComponent(component.id)
      .then((full) => { if (!cancelled) setDetail(full); })
      .catch((err) => {
        if (!cancelled) setLoadError(err.message || 'Failed to load component details.');
      });
    return () => { cancelled = true; };
  }, [component?.id, component?.name, component?.codeName]);

  if (!component) return null;

  const merged = detail || component;
  const label = componentLabel(merged);
  const isImplementation = merged.docType === 'IMPLEMENTATION';
  const { source } = extractSourceMeta(merged.content || '');
  const contentLines = !isImplementation && merged.content
    ? merged.content.split('\n').map((line) => line.trim()).filter(Boolean)
        .filter((line) => !isImgHeaderLine(line))
        .map(cleanContentLine)
    : [];

  function startEditing() {
    setDraftName(merged.codeName || merged.name || '');
    setIsEditing(true);
  }

  function cancelEditing() {
    setIsEditing(false);
    setDraftName(merged.codeName || merged.name || '');
  }

  async function handleSaveName() {
    const trimmed = draftName.trim();
    if (!trimmed) {
      setIsEditing(false);
      return;
    }
    setSaving(true);
    try {
      const looksLikeCode = /^[A-Za-z]{1,6}[-\s_]?\d{1,3}$/.test(trimmed)
        || (isImplementation && trimmed.length <= 64 && !trimmed.includes(' '));
      const payload = looksLikeCode
        ? { codeName: trimmed, name: merged.name || trimmed }
        : { name: trimmed };
      const updated = await renameTraceComponent(component.id, payload);
      setDetail(updated);
      onRenamed?.(updated);
      setIsEditing(false);
      showToast?.(looksLikeCode ? 'Code name updated.' : 'Component renamed.', 'success');
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setSaving(false);
    }
  }

  return (
    <AppModal
      isOpen={Boolean(component)}
      onClose={onClose}
      containerClassName={isImplementation ? 'tm-detail-modal--code' : ''}
      title={
        isEditing ? (
          <div className="tm-rename-row">
            <input
              className="pw-input"
              type="text"
              autoFocus
              value={draftName}
              onChange={(e) => setDraftName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleSaveName();
                if (e.key === 'Escape') cancelEditing();
              }}
              disabled={saving}
              placeholder={isImplementation ? 'FileName.java' : 'UC-01'}
            />
            <button className="tm-icon-btn" title="Save" onClick={handleSaveName} disabled={saving || !draftName.trim()}>
              {saving ? <Loader2 size={14} className="tm-spin" /> : <Check size={14} />}
            </button>
            <button className="tm-icon-btn" title="Cancel" onClick={cancelEditing} disabled={saving}>
              <X size={14} />
            </button>
          </div>
        ) : (
          <div className="tm-rename-row">
            <span className="tm-detail__code">{label}</span>
            {!readOnly && (
              <button className="tm-icon-btn" title="Edit code / name" onClick={startEditing}>
                <Pencil size={14} />
              </button>
            )}
          </div>
        )
      }
      subtitle={
        <span className="tm-badge" data-doctype={component.docType}>{component.docType} COMPONENT</span>
      }
    >
      <div className="tm-detail">
        {loadError && <p className="tm-muted">{loadError}</p>}

        {!isImplementation && merged.imageData && (
          <img
            className="tm-detail__image"
            src={`data:image/jpeg;base64,${merged.imageData}`}
            alt={`${label} diagram page`}
            title="Click to enlarge page"
            onClick={() => setImageExpanded(true)}
          />
        )}

        {!isImplementation && !merged.imageData && detail === null && !loadError && (
          <p className="tm-muted">Loading page image...</p>
        )}

        {!isImplementation && !merged.imageData && detail !== null && (
          <p className="tm-muted">No page image stored for this component.</p>
        )}

        {isImplementation ? (
          <div className="tm-detail__code-panel">
            {detail === null && !loadError ? (
              <p className="tm-muted">Loading source code from GitHub ingest...</p>
            ) : source ? (
              <pre className="tm-detail__source"><code>{source}</code></pre>
            ) : (
              <p className="tm-muted">No source code stored for this file. Re-run GitHub ingest.</p>
            )}
          </div>
        ) : (
          <div className="tm-detail__box">
            {contentLines.length > 1 ? (
              <ul className="tm-detail__list">
                {contentLines.map((line, i) => (
                  <li key={i}>{line}</li>
                ))}
              </ul>
            ) : (
              merged.content || merged.name || label
            )}
          </div>
        )}

        {(() => {
          const provenance = describeProvenance(merged);
          return (
            <p className="tm-muted tm-detail__provenance" style={{ marginTop: '0.75rem' }}>
              {provenance.url ? (
                <a href={provenance.url} target="_blank" rel="noreferrer">{provenance.label}</a>
              ) : (
                provenance.label
              )}
              {provenance.dateLabel && <span> &middot; {provenance.dateLabel}</span>}
            </p>
          );
        })()}
      </div>

      {imageExpanded && merged.imageData && (
        <div className="tm-lightbox" onClick={() => setImageExpanded(false)}>
          <button
            className="tm-lightbox__close"
            onClick={() => setImageExpanded(false)}
            aria-label="Close"
          >
            <X size={20} />
          </button>
          <img
            src={`data:image/jpeg;base64,${merged.imageData}`}
            alt={`${label} diagram page`}
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}
    </AppModal>
  );
}

export default ComponentDetailModal;
