import { componentLabel } from '../../../constants';

function itemLabel(item) {
  if (!item) return null;
  return item.name ? componentLabel(item) : item.description;
}

function MappingControls({ stage, sourceItems, targetItems, selectedSourceId, selectedTargetId, mappingsForStage, onEstablish, onRemoveMapping }) {
  const sourceById = new Map(sourceItems.map((i) => [i.id, i]));
  const targetById = new Map(targetItems.map((i) => [i.id, i]));

  const selectedSource = selectedSourceId ? sourceById.get(selectedSourceId) : null;
  const selectedTarget = selectedTargetId ? targetById.get(selectedTargetId) : null;
  const canEstablish = Boolean(selectedSourceId && selectedTargetId);

  return (
    <div className="stm-controls">
      <div className="stm-controls__preview">
        <div className={`stm-controls__slot ${selectedSource ? 'stm-controls__slot--filled' : ''}`}>
          <span className="stm-controls__slot-label">Source</span>
          <span className="stm-controls__slot-value">{itemLabel(selectedSource) || 'Select a source component'}</span>
        </div>
        <span className="stm-controls__arrow">to</span>
        <div className={`stm-controls__slot ${selectedTarget ? 'stm-controls__slot--filled' : ''}`}>
          <span className="stm-controls__slot-label">Target</span>
          <span className="stm-controls__slot-value">{itemLabel(selectedTarget) || 'Select a target component'}</span>
        </div>
      </div>

      <button className="btn btn--primary stm-controls__establish" disabled={!canEstablish} onClick={onEstablish}>
        Establish Mapping
      </button>

      <div className="stm-controls__list-header">{stage.label} mappings ({mappingsForStage.length})</div>
      {mappingsForStage.length === 0 ? (
        <p className="tm-muted stm-controls__empty">No mappings yet for this stage.</p>
      ) : (
        <ul className="stm-controls__list">
          {mappingsForStage.map((m) => {
            const src = sourceById.get(m.sourceId);
            const tgt = targetById.get(m.targetId);
            return (
              <li key={m.id} className="stm-controls__list-item">
                <span className="stm-controls__pair">
                  <span title={itemLabel(src)}>{itemLabel(src) || 'Removed component'}</span>
                  <span className="stm-controls__pair-sep">to</span>
                  <span title={itemLabel(tgt)}>{itemLabel(tgt) || 'Removed component'}</span>
                </span>
                <button className="stm-mini-btn" onClick={() => onRemoveMapping(m.id)}>
                  Remove
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

export default MappingControls;
