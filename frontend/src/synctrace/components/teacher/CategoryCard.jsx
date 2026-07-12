import { CircleCheck, Plus, TriangleAlert, X } from 'lucide-react';

const CATEGORY_LABELS = {
  SRS: 'SRS Reference',
  SDD: 'SDD Reference',
  SPMP: 'SPMP Reference',
  STD: 'STD Reference',
  IMPLEMENTATION: 'Implementation Component',
};

function CategoryCard({ docType, components, complete, onAdd, onRemove }) {
  return (
    <section className={`tm-card ${complete ? 'tm-card--ok' : 'tm-card--warn'}`}>
      <div className="tm-card__header">
        <span>{CATEGORY_LABELS[docType].toUpperCase()}</span>
        <span className={`tm-status-icon ${complete ? 'tm-status-icon--ok' : 'tm-status-icon--warn'}`}>
          {complete ? <CircleCheck size={16} /> : <TriangleAlert size={16} />}
        </span>
      </div>

      {components.length > 0 ? (
        <div className="tm-card__list">
          {components.map((c) => (
            <div key={c.id} className="tm-mapped-item">
              <span>{c.name}</span>
              <button className="tm-icon-btn" title="Remove mapping" onClick={() => onRemove(c.id)}>
                <X size={14} />
              </button>
            </div>
          ))}
          <button className="tm-add-component" onClick={onAdd}>
            <Plus size={14} /> Add Component
          </button>
        </div>
      ) : (
        <button className="tm-card__empty" onClick={onAdd}>
          <span className="tm-card__empty-icon"><Plus size={18} /></span>
          <span>No components mapped</span>
        </button>
      )}
    </section>
  );
}

export default CategoryCard;
