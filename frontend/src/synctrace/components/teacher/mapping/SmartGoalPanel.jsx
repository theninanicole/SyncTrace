import { useState } from 'react';
import { groupGoalsIntoClusters } from '../../../constants';

/** Inline textarea for adding or rewording a goal. */
function GoalEditor({ initialValue = '', placeholder, saveLabel = 'Save', onSave, onCancel }) {
  const [value, setValue] = useState(initialValue);
  const [saving, setSaving] = useState(false);
  const trimmed = value.trim();
  const unchanged = trimmed === initialValue.trim();

  async function handleSave() {
    if (!trimmed || unchanged) return;
    setSaving(true);
    const ok = await onSave(trimmed);
    setSaving(false);
    if (ok) onCancel();
  }

  return (
    <div className="stm-goal-editor">
      <textarea
        className="stm-goal-editor__input"
        value={value}
        placeholder={placeholder}
        rows={3}
        autoFocus
        disabled={saving}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') onCancel();
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSave();
        }}
      />
      <div className="stm-goal-editor__actions">
        <button className="stm-mini-btn" onClick={onCancel} disabled={saving}>Cancel</button>
        <button className="stm-mini-btn stm-mini-btn--primary" onClick={handleSave} disabled={saving || !trimmed || unchanged}>
          {saving ? 'Saving…' : saveLabel}
        </button>
      </div>
    </div>
  );
}

/** Edit / Delete buttons; Delete asks for confirmation in place. */
function GoalActions({ confirmingDelete, deleteHint, onEdit, onDelete, onConfirmDelete, onCancelDelete }) {
  if (confirmingDelete) {
    return (
      <div className="stm-goal-actions stm-goal-actions--confirm">
        <span className="stm-goal-actions__hint">{deleteHint}</span>
        <button className="stm-mini-btn" onClick={onCancelDelete}>Cancel</button>
        <button className="stm-mini-btn stm-mini-btn--danger" onClick={onConfirmDelete}>Delete</button>
      </div>
    );
  }
  return (
    <div className="stm-goal-actions">
      <button className="stm-mini-btn" onClick={onEdit}>Edit</button>
      <button className="stm-mini-btn" onClick={onDelete}>Delete</button>
    </div>
  );
}

function SmartGoalPanel({
  heading,
  smartGoalsExtracted,
  smartGoals,
  loading,
  extracting,
  selectedId,
  linkedIds,
  mappedCounts,
  onSelect,
  onExtractClick,
  onAddGoal,
  onUpdateGoal,
  onDeleteGoal,
  readOnly = false,
}) {
  // One open editor at a time: { mode: 'edit', id } | { mode: 'add-general' } | { mode: 'add-specific', parentId }
  const [editor, setEditor] = useState(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState(null);
  const clusters = groupGoalsIntoClusters(smartGoals);
  const canEdit = !readOnly && Boolean(onUpdateGoal);

  const closeEditor = () => setEditor(null);
  const startEdit = (id) => {
    setConfirmDeleteId(null);
    setEditor({ mode: 'edit', id });
  };
  const startDelete = (id) => {
    setEditor(null);
    setConfirmDeleteId(id);
  };
  const confirmDelete = async (id) => {
    await onDeleteGoal(id);
    setConfirmDeleteId(null);
  };

  function renderBody() {
    if (extracting) {
      return (
        <div className="stm-column-empty">
          <p>Extracting SMART goals from the evaluated proposal…</p>
        </div>
      );
    }

    if (loading && clusters.length === 0) {
      return (
        <div className="stm-column-empty">
          <p>Loading SMART goals…</p>
        </div>
      );
    }

    if (!smartGoalsExtracted) {
      return (
        <div className="stm-column-empty">
          <p>No SMART Goals available yet.</p>
          <p className="tm-muted">Extract the structured General and Specific objectives from the evaluated proposal to begin Proposal → SRS mapping.</p>
          {!readOnly && <button className="btn btn--primary" onClick={onExtractClick}>Extract SMART Goals</button>}
        </div>
      );
    }

    if (clusters.length === 0) {
      return (
        <div className="stm-column-empty">
          <p>The proposal did not yield any SMART goals.</p>
          {!readOnly && <button className="btn btn--soft" onClick={onExtractClick}>Re-extract SMART Goals</button>}
        </div>
      );
    }

    return (
      <div className="stm-goal-tree">
        {clusters.map((cluster, clusterIndex) => {
          const count = mappedCounts?.[cluster.id] || 0;
          const isSelected = selectedId === cluster.id;
          const isLinked = linkedIds?.has(cluster.id);
          const isGeneral = cluster.primary.goalKind === 'GENERAL';
          const editingPrimary = editor?.mode === 'edit' && editor.id === cluster.id;
          return (
            <div key={cluster.id} className="stm-goal-cluster">
              {editingPrimary ? (
                <GoalEditor
                  initialValue={cluster.primary.description}
                  onSave={(text) => onUpdateGoal(cluster.id, text)}
                  onCancel={closeEditor}
                />
              ) : (
                <button
                  type="button"
                  className={[
                    'stm-goal-node',
                    isSelected ? 'stm-goal-node--selected' : '',
                    isLinked ? 'stm-goal-node--linked' : '',
                  ].filter(Boolean).join(' ')}
                  onClick={() => onSelect(cluster.id)}
                  aria-pressed={isSelected}
                >
                  {/* Numbered like the Results matrix and Issues panel (G1, G2, …). */}
                  <span className="tm-goal-kind tm-goal-kind--general">G{clusterIndex + 1}</span>
                  <span className="stm-goal-node__text">{cluster.primary.description}</span>
                  {count > 0 && <span className="stm-mapped-count">{count}</span>}
                </button>
              )}
              {canEdit && !editingPrimary && (
                <GoalActions
                  confirmingDelete={confirmDeleteId === cluster.id}
                  deleteHint={cluster.children.length > 0
                    ? `Delete G${clusterIndex + 1}, its ${cluster.children.length} specific objective(s) and its mappings?`
                    : `Delete G${clusterIndex + 1} and its mappings?`}
                  onEdit={() => startEdit(cluster.id)}
                  onDelete={() => startDelete(cluster.id)}
                  onConfirmDelete={() => confirmDelete(cluster.id)}
                  onCancelDelete={() => setConfirmDeleteId(null)}
                />
              )}
              {cluster.children.length > 0 && (
                <ul className="tm-goal-item__children tm-goal-summary__children">
                  {cluster.children.map((child) => {
                    const editingChild = editor?.mode === 'edit' && editor.id === child.id;
                    return (
                      <li key={child.id} className={canEdit ? 'stm-goal-child' : undefined}>
                        {editingChild ? (
                          <GoalEditor
                            initialValue={child.description}
                            onSave={(text) => onUpdateGoal(child.id, text)}
                            onCancel={closeEditor}
                          />
                        ) : (
                          <>
                            <span className="stm-goal-child__text">{child.description}</span>
                            {canEdit && (
                              <GoalActions
                                confirmingDelete={confirmDeleteId === child.id}
                                deleteHint="Delete this specific objective?"
                                onEdit={() => startEdit(child.id)}
                                onDelete={() => startDelete(child.id)}
                                onConfirmDelete={() => confirmDelete(child.id)}
                                onCancelDelete={() => setConfirmDeleteId(null)}
                              />
                            )}
                          </>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
              {canEdit && isGeneral && (
                editor?.mode === 'add-specific' && editor.parentId === cluster.id ? (
                  <GoalEditor
                    placeholder="Specific objective"
                    saveLabel="Add"
                    onSave={(text) => onAddGoal(text, cluster.id)}
                    onCancel={closeEditor}
                  />
                ) : (
                  <button
                    className="stm-mini-btn stm-goal-cluster__add"
                    onClick={() => {
                      setConfirmDeleteId(null);
                      setEditor({ mode: 'add-specific', parentId: cluster.id });
                    }}
                  >
                    Add specific objective
                  </button>
                )
              )}
            </div>
          );
        })}
      </div>
    );
  }

  return (
    <div className="stm-column">
      <div className="stm-column__header">
        <span>{heading}</span>
        {canEdit && (
          <button
            className="stm-mini-btn"
            onClick={() => {
              setConfirmDeleteId(null);
              setEditor({ mode: 'add-general' });
            }}
          >
            Add
          </button>
        )}
      </div>
      {editor?.mode === 'add-general' && (
        <GoalEditor
          placeholder="General objective"
          saveLabel="Add"
          onSave={(text) => onAddGoal(text, null)}
          onCancel={closeEditor}
        />
      )}
      {renderBody()}
    </div>
  );
}

export default SmartGoalPanel;
