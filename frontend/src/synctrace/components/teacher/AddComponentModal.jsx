import { useEffect, useState } from 'react';
import { Eye, Plus, Trash2 } from 'lucide-react';
import AppModal from '../../../components/common/AppModal';
import { getTraceComponents, createTraceComponent, deleteTraceComponent } from '../../api';
import { DOC_TYPES } from '../../hooks/useTraceability';
import ComponentDetailModal from './ComponentDetailModal';
import ConfirmModal from '../common/ConfirmModal';

const FILTERS = [{ key: 'ALL', label: 'All' }, ...DOC_TYPES.map((dt) => ({ key: dt, label: dt }))];

function AddComponentModal({ isOpen, onClose, initialDocType = 'ALL', excludeComponentIds = [], onAddSelected, showToast }) {
  const [activeFilter, setActiveFilter] = useState(initialDocType);
  const [search, setSearch]             = useState('');
  const [components, setComponents]     = useState([]);
  const [loading, setLoading]           = useState(false);
  const [selectedIds, setSelectedIds]   = useState(new Set());
  const [previewComponent, setPreviewComponent] = useState(null);
  const [showNewForm, setShowNewForm]   = useState(false);
  const [newName, setNewName]           = useState('');
  const [creating, setCreating]         = useState(false);
  const [pendingDelete, setPendingDelete] = useState(null);
  const [deleting, setDeleting]         = useState(false);

  useEffect(() => {
    if (isOpen) {
      setActiveFilter(initialDocType);
      setSearch('');
      setSelectedIds(new Set());
      setShowNewForm(false);
      setNewName('');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    setLoading(true);
    const docType = activeFilter === 'ALL' ? undefined : activeFilter;
    getTraceComponents(docType, search || undefined)
      .then((data) => { if (!cancelled) setComponents(data); })
      .catch((err) => { if (!cancelled) showToast?.(err.message, 'error'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [isOpen, activeFilter, search, showToast]);

  if (!isOpen) return null;

  const excludeSet = new Set(excludeComponentIds);
  const visibleComponents = components.filter((c) => !excludeSet.has(c.id));

  function toggleSelected(id) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function handleCreateNewComponent() {
    if (!newName.trim()) return;
    if (activeFilter === 'ALL') {
      showToast?.('Pick a specific category tab before adding a new component.', 'error');
      return;
    }
    setCreating(true);
    try {
      const created = await createTraceComponent(activeFilter, newName.trim(), newName.trim());
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
        title="Add Components"
        subtitle="Select multiple existing artifacts to link to this goal."
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
          placeholder="Search components..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />

        <div className="pw-tabs" style={{ margin: '0.85rem 0' }}>
          {FILTERS.map(({ key, label }) => (
            <button
              key={key}
              className={`pw-tab ${activeFilter === key ? 'pw-tab--active' : ''}`}
              onClick={() => setActiveFilter(key)}
            >
              {label}
            </button>
          ))}
        </div>

        {loading ? (
          <p className="tm-muted">Loading components...</p>
        ) : visibleComponents.length === 0 ? (
          <div className="empty-state">
            <p>No components found{search ? ` for "${search}"` : ''}.</p>
          </div>
        ) : (
          <div className="tm-component-list">
            {visibleComponents.map((c) => {
              const checked = selectedIds.has(c.id);
              return (
                <div key={c.id} className={`tm-component-row ${checked ? 'tm-component-row--checked' : ''}`}>
                  <label className="tm-component-row__main">
                    <input type="checkbox" checked={checked} onChange={() => toggleSelected(c.id)} />
                    <span className="tm-badge" data-doctype={c.docType}>{c.docType}</span>
                    <span className="tm-component-row__name">{c.name}</span>
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
        )}

        <div className="tm-new-component">
          {showNewForm ? (
            <div className="tm-new-component__form">
              <input
                className="pw-input"
                type="text"
                placeholder={activeFilter === 'ALL' ? 'Pick a category tab first...' : `New ${activeFilter} component name`}
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                disabled={activeFilter === 'ALL'}
              />
              <button className="btn" onClick={() => setShowNewForm(false)}>Cancel</button>
              <button className="btn btn--soft" disabled={creating || !newName.trim()} onClick={handleCreateNewComponent}>
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
