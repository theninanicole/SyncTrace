import { useEffect, useState } from 'react';
import { Check, Loader2, Pencil, X } from 'lucide-react';
import AppModal from '../../../components/common/AppModal';
import { renameTraceComponent, getTraceComponent } from '../../api';
import { ARTIFACT_KINDS_BY_DOC_TYPE, DOC_TYPE_LABELS, componentLabel } from '../../constants';
import { formatDate } from '../../../utils/dashboardUtils';

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

/**
 * A type correction offers the documents of the stage being mapped, or only the component's own
 * document outside a stage. It can change the document family, but never turns a document into
 * code or back.
 */
function editableKindGroups(docType, stage) {
  const isCode = docType === 'IMPLEMENTATION';
  const offered = stage ? [stage.sourceType, stage.targetType] : [docType];
  return Object.entries(ARTIFACT_KINDS_BY_DOC_TYPE)
    .filter(([groupDocType]) => (groupDocType === 'IMPLEMENTATION') === isCode)
    .filter(([groupDocType]) => offered.includes(groupDocType));
}

function ComponentDetailModal({ component, stage, onClose, onRenamed, showToast, readOnly = false, extractedTypesOnly = false }) {
  const [isEditing, setIsEditing]     = useState(false);
  const [draftName, setDraftName]     = useState('');
  const [saving, setSaving]           = useState(false);
  const [savingKind, setSavingKind]   = useState(false);
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
      // The label shown everywhere is the code name, so save exactly what was typed.
      const updated = await renameTraceComponent(component.id, { codeName: trimmed });
      setDetail(updated);
      onRenamed?.(updated);
      setIsEditing(false);
      showToast?.('Component renamed.', 'success');
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setSaving(false);
    }
  }

  async function handleKindChange(artifactKind) {
    const newDocType = Object.keys(ARTIFACT_KINDS_BY_DOC_TYPE)
      .find((docType) => ARTIFACT_KINDS_BY_DOC_TYPE[docType].some((o) => o.value === artifactKind));
    if (newDocType !== merged.docType && !window.confirm(
      `This moves ${label} from the ${DOC_TYPE_LABELS[merged.docType]} library to the ${DOC_TYPE_LABELS[newDocType]} library and removes its mappings. Continue?`
    )) return;

    setSavingKind(true);
    try {
      const updated = await renameTraceComponent(component.id, { artifactKind });
      setDetail(updated);
      onRenamed?.(updated);
      showToast?.('Component type updated.', 'success');
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setSavingKind(false);
    }
  }

  const kindGroups = editableKindGroups(merged.docType, stage);
  const hasListedKind = kindGroups.some(([, options]) => options.some((o) => o.value === detail?.artifactKind));
  const canEditKind = !readOnly && (!extractedTypesOnly || detail?.aiExtracted);

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
              maxLength={64}
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
        <span className="tm-badge" data-doctype={merged.docType}>{merged.docType} COMPONENT</span>
      }
    >
      <div className="tm-detail">
        {loadError && <p className="tm-muted">{loadError}</p>}

        {canEditKind && (
          <div className="tm-detail__type">
            <label className="tm-field-label" htmlFor="component-type">Component type</label>
            <select
              id="component-type"
              className="tm-select"
              value={hasListedKind ? detail.artifactKind : ''}
              disabled={detail === null || savingKind}
              onChange={(e) => handleKindChange(e.target.value)}
            >
              {!hasListedKind && <option value="" disabled>Select a type</option>}
              {kindGroups.map(([docType, options]) => (
                <optgroup key={docType} label={DOC_TYPE_LABELS[docType]}>
                  {options.map((opt) => (
                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>
        )}

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

        {isImplementation && (
          <>
            <div className="tm-detail__code-panel">
              {detail === null && !loadError ? (
                <p className="tm-muted">Loading source code from GitHub ingest...</p>
              ) : source ? (
                <pre className="tm-detail__source"><code>{source}</code></pre>
              ) : (
                <p className="tm-muted">No source code stored for this file. Re-run GitHub ingest.</p>
              )}
            </div>

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
          </>
        )}
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
