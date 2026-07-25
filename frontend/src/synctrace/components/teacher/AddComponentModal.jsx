import { useEffect, useMemo, useState } from 'react';
import { Eye, Plus, Trash2 } from 'lucide-react';
import AppModal from '../../../components/common/AppModal';
import { getTraceComponents, createTraceComponent, deleteTraceComponent } from '../../api';
import { getEvaluationHistory } from '../../../api';
import { extractSubmissionMeta } from '../../../utils/dashboardUtils';
import { ARTIFACT_KINDS_BY_DOC_TYPE, artifactKindLabel, componentLabel } from '../../constants';
import ComponentDetailModal from './ComponentDetailModal';
import ConfirmModal from '../common/ConfirmModal';

const DOC_TITLES = {
  SRS: 'SRS — Requirements artifacts',
  SDD: 'SDD — Design artifacts',
  SPMP: 'SPMP — Project management artifacts',
  STD: 'STD — Test artifacts',
  IMPLEMENTATION: 'Implementation — Source code artifacts',
};

function AddComponentModal({ isOpen, onClose, initialDocType = 'SRS', excludeComponentIds = [], onAddSelected, onComponentRenamed, showToast, teamCode = '' }) {
  const docType = initialDocType === 'ALL' ? 'SRS' : initialDocType;

  const [search, setSearch]             = useState('');
  const [components, setComponents]     = useState([]);
  const [loading, setLoading]           = useState(false);
  const [historyTeamMap, setHistoryTeamMap] = useState(new Map());
  const [selectedIds, setSelectedIds]   = useState(new Set());
  const [previewComponent, setPreviewComponent] = useState(null);
  const [showNewForm, setShowNewForm]   = useState(false);
  const [newName, setNewName]           = useState('');
  const [newArtifactKind, setNewArtifactKind] = useState('');
  const [creating, setCreating]         = useState(false);
  const [pendingDelete, setPendingDelete] = useState(null);
  const [deleting, setDeleting]         = useState(false);

  const artifactOptions = ARTIFACT_KINDS_BY_DOC_TYPE[docType] || [];

  useEffect(() => {
    if (isOpen) {
      setSearch('');
      setSelectedIds(new Set());
      setShowNewForm(false);
      setNewName('');
      setNewArtifactKind((ARTIFACT_KINDS_BY_DOC_TYPE[docType] || [])[0]?.value || '');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, docType]);

  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    setLoading(true);
    getTraceComponents(docType, search || undefined)
      .then((data) => { if (!cancelled) setComponents(data); })
      .catch((err) => { if (!cancelled) showToast?.(err.message, 'error'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [isOpen, docType, search, showToast]);

  // TraceComponent has no teamCode of its own — resolve it via the submission
  // (sourceHistoryId) it was extracted from, same as the rest of the app.
  useEffect(() => {
    if (!isOpen || !teamCode) return;
    let cancelled = false;
    getEvaluationHistory()
      .then((items) => {
        if (cancelled) return;
        const map = new Map();
        items.forEach((h) => {
          const meta = extractSubmissionMeta(h.fileName);
          if (meta.teamCode) map.set(h.id, meta.teamCode);
        });
        setHistoryTeamMap(map);
      })
      .catch(() => { if (!cancelled) setHistoryTeamMap(new Map()); });
    return () => { cancelled = true; };
  }, [isOpen, teamCode]);

  const excludeSet = useMemo(() => new Set(excludeComponentIds), [excludeComponentIds]);

  /** Components grouped by artifact kind, ordered like the taxonomy. */
  const groupedComponents = useMemo(() => {
    let visible = components.filter((c) => !excludeSet.has(c.id));
    if (teamCode) {
      visible = visible.filter((c) => {
        // Manually-added components have no source submission — always show them.
        if (c.sourceHistoryId == null) return true;
        const resolvedTeam = historyTeamMap.get(c.sourceHistoryId);
        return !resolvedTeam || resolvedTeam.toUpperCase() === teamCode.toUpperCase();
      });
    }
    const knownKinds = artifactOptions.map((o) => o.value);
    const groups = [];

    knownKinds.forEach((kind) => {
      const items = visible.filter((c) => c.artifactKind === kind);
      if (items.length > 0) {
        groups.push({ kind, label: artifactKindLabel(kind), items });
      }
    });

    const leftovers = visible.filter((c) => !knownKinds.includes(c.artifactKind));
    if (leftovers.length > 0) {
      groups.push({ kind: 'UNSPECIFIED', label: 'Untyped', items: leftovers });
    }

    return groups;
  }, [components, excludeSet, artifactOptions, teamCode, historyTeamMap]);

  if (!isOpen) return null;

  const visibleCount = groupedComponents.reduce((sum, g) => sum + g.items.length, 0);

  function toggleSelected(id) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function handleCreateNewComponent() {
    if (!newName.trim() || !newArtifactKind) return;
    setCreating(true);
    try {
      const created = await createTraceComponent(
        docType,
        newName.trim(),
        newName.trim(),
        newArtifactKind,
      );
      setComponents((prev) => [created, ...prev]);
      setSelectedIds((prev) => new Set(prev).add(created.id));
      setNewName('');
      setShowNewForm(false);
      showToast?.('Component added to the library.', 'success');
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setCreating(false);
    }
  }

  function handleAddSelected() {
    onAddSelected(Array.from(selectedIds));
  }

  function handleComponentRenamed(updated) {
    setComponents((prev) => prev.map((c) => (c.id === updated.id ? updated : c)));
    setPreviewComponent(updated);
    onComponentRenamed?.(updated);
  }

  async function confirmDeleteComponent() {
    if (!pendingDelete) return;
    setDeleting(true);
    try {
      await deleteTraceComponent(pendingDelete.id);
      setComponents((prev) => prev.filter((c) => c.id !== pendingDelete.id));
      setSelectedIds((prev) => {
        if (!prev.has(pendingDelete.id)) return prev;
        const next = new Set(prev);
        next.delete(pendingDelete.id);
        return next;
      });
      if (previewComponent?.id === pendingDelete.id) setPreviewComponent(null);
      showToast?.('Component deleted.', 'success');
      setPendingDelete(null);
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setDeleting(false);
    }
  }

  return (
    <>
      <AppModal
        isOpen={isOpen}
        onClose={onClose}
        title={DOC_TITLES[docType] || `Add ${docType} components`}
        subtitle="Select the specific artifacts that this goal maps to."
        containerClassName="tm-add-modal"
        footer={
          <div className="modal-actions" style={{ justifyContent: 'space-between', width: '100%' }}>
            <span className="tm-muted">{selectedIds.size} component(s) selected</span>
            <div className="modal-actions">
              <button className="btn" onClick={onClose}>Cancel</button>
              <button className="btn btn--primary" disabled={selectedIds.size === 0} onClick={handleAddSelected}>
                Add Selected
              </button>
            </div>
          </div>
        }
      >
        <input
          type="search"
          className="teacher-header-search tm-search"
          placeholder={`Search ${docType === 'IMPLEMENTATION' ? 'code' : docType} components...`}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />

        {loading ? (
          <p className="tm-muted">Loading components...</p>
        ) : visibleCount === 0 ? (
          <div className="empty-state">
            <p>No {docType === 'IMPLEMENTATION' ? 'implementation' : docType} components found{search ? ` for "${search}"` : ''}.</p>
            <p className="tm-muted">
              Build the library first: extract from evaluated documents, ingest the GitHub repository, or add one manually below.
            </p>
          </div>
        ) : (
          <div className="tm-component-list">
            {groupedComponents.map((group) => (
              <div key={group.kind} className="tm-component-group">
                <div className="tm-component-group__header">
                  {group.label}
                  <span className="tm-component-group__count">{group.items.length}</span>
                </div>
                {group.items.map((c) => {
                  const checked = selectedIds.has(c.id);
                  return (
                    <div key={c.id} className={`tm-component-row ${checked ? 'tm-component-row--checked' : ''}`}>
                      <label className="tm-component-row__main">
                        <input type="checkbox" checked={checked} onChange={() => toggleSelected(c.id)} />
                        <span className="tm-component-row__name">
                          <span title={c.name || componentLabel(c)}>
                            {componentLabel(c)}
                          </span>
                          {c.name && c.name !== componentLabel(c) && (
                            <span className="tm-component-row__fullname">{c.name}</span>
                          )}
                        </span>
                      </label>
                      <div className="tm-component-row__actions">
                        <button className="tm-icon-btn" title="Preview" onClick={() => setPreviewComponent(c)}>
                          <Eye size={14} />
                        </button>
                        <button className="tm-icon-btn tm-icon-btn--danger" title="Delete component" onClick={() => setPendingDelete(c)}>
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            ))}
          </div>
        )}

        <div className="tm-new-component">
          {showNewForm ? (
            <div className="tm-new-component__form">
              <input
                className="pw-input"
                type="text"
                placeholder={`New ${docType} component name (e.g. UC-07: Export Audit Report)`}
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
              />
              <select
                className="tm-select"
                value={newArtifactKind}
                onChange={(e) => setNewArtifactKind(e.target.value)}
              >
                {artifactOptions.map((opt) => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
              <button className="btn" onClick={() => setShowNewForm(false)}>Cancel</button>
              <button className="btn btn--soft" disabled={creating || !newName.trim() || !newArtifactKind} onClick={handleCreateNewComponent}>
                {creating ? 'Adding...' : 'Add to Library'}
              </button>
            </div>
          ) : (
            <button className="pw-btn--link tm-link-btn" onClick={() => setShowNewForm(true)}>
              <Plus size={12} /> Add a new component manually
            </button>
          )}
        </div>
      </AppModal>

      <ComponentDetailModal
        component={previewComponent}
        onClose={() => setPreviewComponent(null)}
        onRenamed={handleComponentRenamed}
        showToast={showToast}
      />

      <ConfirmModal
        isOpen={Boolean(pendingDelete)}
        title="Delete component"
        message={`Delete "${pendingDelete?.name}" from the component library? This also removes it from any goals it's mapped to.`}
        confirmLabel="Delete"
        danger
        submitting={deleting}
        onConfirm={confirmDeleteComponent}
        onCancel={() => setPendingDelete(null)}
      />
    </>
  );
}

export default AddComponentModal;
