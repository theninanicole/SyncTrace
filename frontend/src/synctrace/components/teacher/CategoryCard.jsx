import { CircleCheck, Plus, TriangleAlert, X } from 'lucide-react';
import { artifactKindLabel, componentLabel, preferredArtifactHint } from '../../constants';

const CATEGORY_LABELS = {
  SRS: 'SRS Reference',
  SDD: 'SDD Reference',
  SPMP: 'SPMP Reference',
  STD: 'STD Reference',
  IMPLEMENTATION: 'Implementation Component',
};

const EXAMPLE_BY_DOC_TYPE = {
  SRS: 'e.g. UC-07: Export Audit Report',
  SDD: 'e.g. SD-3: Gap detection sequence',
  SPMP: 'e.g. Milestone M4',
  STD: 'e.g. TC-14: Gap detector test',
  IMPLEMENTATION: 'e.g. GapDetector.ts',
};

function CategoryCard({ docType, components, complete, onAdd, onRemove, onComponentClick, goalKind = 'SPECIFIC' }) {
  const emptyHint = preferredArtifactHint(goalKind);

  return (
    <section className={`tm-card ${complete ? 'tm-card--ok' : 'tm-card--warn'}`}>
      <div className="tm-card__header">
        <span>{CATEGORY_LABELS[docType].toUpperCase()}</span>
        <span className={`tm-status-icon ${complete ? 'tm-status-icon--ok' : 'tm-status-icon--warn'}`}>
          {complete ? <CircleCheck size={16} /> : <TriangleAlert size={16} />}
        </span>
      </div>

      {components.length > 0 ? (
        <>
          <div className="tm-card__list">
            {components.map((c) => (
              <div key={c.id} className="tm-mapped-item">
                <button
                  type="button"
                  className="tm-mapped-item__body tm-mapped-item__body--link"
                  onClick={() => onComponentClick?.(c)}
                  title={c.name || componentLabel(c)}
                >
                  <span className="tm-mapped-item__name">{componentLabel(c)}</span>
                  {artifactKindLabel(c.artifactKind) && (
                    <span className="tm-artifact-kind">{artifactKindLabel(c.artifactKind)}</span>
                  )}
                </button>
                <button className="tm-icon-btn" title="Remove mapping" onClick={() => onRemove(c.id)}>
                  <X size={14} />
                </button>
              </div>
            ))}
          </div>
          <button className="tm-add-component" onClick={onAdd}>
            <Plus size={14} /> Add Component
          </button>
        </>
      ) : (
        <button className="tm-card__empty" onClick={onAdd}>
          <span className="tm-card__empty-icon"><Plus size={18} /></span>
          <span>Pick the exact artifact this goal maps to ({EXAMPLE_BY_DOC_TYPE[docType]})</span>
          <span className="tm-card__empty-hint">{emptyHint}</span>
        </button>
      )}
    </section>
  );
}

export default CategoryCard;
