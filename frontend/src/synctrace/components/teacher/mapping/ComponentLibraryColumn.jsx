import { useMemo, useState } from 'react';
import { componentLabel } from '../../../constants';

function ComponentLibraryColumn({
  heading,
  docType,
  items,
  loading,
  selectedId,
  linkedIds,
  mappedCounts,
  onSelect,
  onAdd,
  onPreview,
  onRemove,
  emptyHint,
}) {
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return items;
    return items.filter(
      (c) => c.name.toLowerCase().includes(q) || c.type.toLowerCase().includes(q)
    );
  }, [items, query]);

  return (
    <div className="stm-column">
      <div className="stm-column__header">
        <span>{heading}</span>
        <button className="stm-mini-btn" onClick={onAdd}>
          Add
        </button>
      </div>

      <label className="stm-search">
        <input
          type="text"
          placeholder={`Search ${docType.toLowerCase()} components…`}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </label>

      {loading ? (
        <div className="stm-column-empty">
          <p>Loading {docType} components…</p>
        </div>
      ) : items.length === 0 ? (
        <div className="stm-column-empty">
          <p>No {docType} components are currently available.</p>
          <p className="tm-muted">{emptyHint}</p>
          <button className="btn btn--soft" onClick={onAdd}>
            Add Component
          </button>
        </div>
      ) : filtered.length === 0 ? (
        <p className="tm-muted stm-column-noresults">No components match “{query}”.</p>
      ) : (
        <div className="stm-component-list">
          {filtered.map((c) => {
            const count = mappedCounts?.[c.id] || 0;
            const isSelected = selectedId === c.id;
            const isLinked = linkedIds?.has(c.id);
            return (
              <div
                key={c.id}
                className={[
                  'stm-component-card',
                  isSelected ? 'stm-component-card--selected' : '',
                  isLinked ? 'stm-component-card--linked' : '',
                ].filter(Boolean).join(' ')}
              >
                <button type="button" className="stm-component-card__body" onClick={() => onSelect(c.id)}>
                  <div className="stm-component-card__top">
                    <span className="stm-component-card__name">{componentLabel(c)}</span>
                    {count > 0 && <span className="stm-mapped-count">{count}</span>}
                  </div>
                  <span className="stm-component-card__type">{c.type}</span>
                  {c.description && <p className="stm-component-card__desc">{c.description}</p>}
                </button>
                <div className="stm-component-card__actions">
                  {onPreview && (
                    <button className="stm-mini-btn" onClick={() => onPreview(c)}>
                      Preview
                    </button>
                  )}
                  {onRemove && (
                    <button className="stm-mini-btn" onClick={() => onRemove(c.id)}>
                      Remove
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default ComponentLibraryColumn;
