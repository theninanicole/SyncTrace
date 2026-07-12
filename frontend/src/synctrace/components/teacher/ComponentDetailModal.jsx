import { useEffect, useState } from 'react';
import { Check, Loader2, Pencil, X } from 'lucide-react';
import AppModal from '../../../components/common/AppModal';
import { renameTraceComponent } from '../../api';

function ComponentDetailModal({ component, onClose, onRenamed, showToast }) {
  const [isEditing, setIsEditing]     = useState(false);
  const [draftName, setDraftName]     = useState('');
  const [saving, setSaving]           = useState(false);
  const [imageExpanded, setImageExpanded] = useState(false);

  useEffect(() => {
    setIsEditing(false);
    setDraftName(component?.name || '');
    setImageExpanded(false);
  }, [component?.id, component?.name]);

  if (!component) return null;

  const contentLines = component.content
    ? component.content.split('\n').map((line) => line.trim()).filter(Boolean)
    : [];

  function startEditing() {
    setDraftName(component.name);
    setIsEditing(true);
  }

  function cancelEditing() {
    setIsEditing(false);
    setDraftName(component.name);
  }

  async function handleSaveName() {
    const trimmed = draftName.trim();
    if (!trimmed || trimmed === component.name) {
      setIsEditing(false);
      return;
    }
    setSaving(true);
    try {
      const updated = await renameTraceComponent(component.id, trimmed);
      onRenamed?.(updated);
      setIsEditing(false);
      showToast?.('Component renamed.', 'success');
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
            />
            <button className="tm-icon-btn" title="Save name" onClick={handleSaveName} disabled={saving || !draftName.trim()}>
              {saving ? <Loader2 size={14} className="tm-spin" /> : <Check size={14} />}
            </button>
            <button className="tm-icon-btn" title="Cancel" onClick={cancelEditing} disabled={saving}>
              <X size={14} />
            </button>
          </div>
        ) : (
          <div className="tm-rename-row">
            <span>{component.name}</span>
            <button className="tm-icon-btn" title="Rename component" onClick={startEditing}>
              <Pencil size={14} />
            </button>
          </div>
        )
      }
      subtitle={
        <span className="tm-badge" data-doctype={component.docType}>{component.docType} COMPONENT</span>
      }
    >
      <div className="tm-detail">
        {component.imageData && (
          <img
            className="tm-detail__image"
            src={`data:image/jpeg;base64,${component.imageData}`}
            alt={`${component.name} diagram`}
            title="Click to enlarge"
            onClick={() => setImageExpanded(true)}
          />
        )}
        <div className="tm-detail__box">
          {contentLines.length > 1 ? (
            <ul className="tm-detail__list">
              {contentLines.map((line, i) => (
                <li key={i}>{line}</li>
              ))}
            </ul>
          ) : (
            component.content || component.name
          )}
        </div>
        {component.aiExtracted && (
          <p className="tm-muted" style={{ marginTop: '0.75rem' }}>
            Auto-extracted from an evaluated submission.
          </p>
        )}
      </div>

      {imageExpanded && component.imageData && (
        <div className="tm-lightbox" onClick={() => setImageExpanded(false)}>
          <button
            className="tm-lightbox__close"
            onClick={() => setImageExpanded(false)}
            aria-label="Close"
          >
            <X size={20} />
          </button>
          <img
            src={`data:image/jpeg;base64,${component.imageData}`}
            alt={`${component.name} diagram`}
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}
    </AppModal>
  );
}

export default ComponentDetailModal;
