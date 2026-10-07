import { componentLabel } from '../../../constants';

/** Short identifier: G1… for SMART goals, the document code (UC-01) for components. */
function itemCode(item) {
  if (!item) return null;
  return item.code || componentLabel(item);
}

/** Longer descriptive text shown next to the code, when it adds anything. */
function itemDetail(item) {
  if (!item) return null;
  if (item.code) return item.description;
  const name = (item.name || '').trim();
  const code = itemCode(item);
  return name && name !== code ? name : item.type;
}

function ItemLine({ item, fallback }) {
  const code = itemCode(item);
  const detail = itemDetail(item);
  return (
    <span className="stm-controls__item" title={[code, detail].filter(Boolean).join(' – ')}>
      <strong>{code || fallback}</strong>
      {detail && <span className="stm-controls__item-detail"> – {detail}</span>}
    </span>
  );
}

/**
 * Group the stage's mapping rows by source so one source with several targets reads as
 * one relationship block. Sources keep the source column's order; rows whose source is no
 * longer listed (removed, or unmapped in the preceding stage) are grouped at the end.
 */
function groupBySource(mappingsForStage, sourceItems, targetById) {
  const groups = new Map();
  mappingsForStage.forEach((m) => {
    if (!groups.has(m.sourceId)) groups.set(m.sourceId, []);
    groups.get(m.sourceId).push(m);
  });
  const order = new Map(sourceItems.map((s, i) => [s.id, i]));
  const byLabel = (a, b) => (itemCode(targetById.get(a.targetId)) || '').localeCompare(
    itemCode(targetById.get(b.targetId)) || '', undefined, { numeric: true, sensitivity: 'base' },
  );
  return [...groups.entries()]
    .sort(([a], [b]) => (order.get(a) ?? Infinity) - (order.get(b) ?? Infinity))
    .map(([sourceId, rows]) => [sourceId, [...rows].sort(byLabel)]);
}

function MappingControls({
  stage,
  sourceItems,
  targetItems,
  selectedSourceId,
  selectedTargetIds,
  mappingsForStage,
  coverage,
  loading = false,
  onEstablish,
  onRemoveMapping,
  onClearTargets,
}) {
  // While goals/components are still loading, a saved mapping's ends can't be resolved yet;
  // say so instead of calling them removed.
  const missingLabel = loading ? 'Loading…' : 'Removed component';
  const sourceById = new Map(sourceItems.map((i) => [i.id, i]));
  const targetById = new Map(targetItems.map((i) => [i.id, i]));
  const isProposalStage = stage.sourceType === 'PROPOSAL';

  const selectedSource = selectedSourceId ? sourceById.get(selectedSourceId) : null;
  const selectedSourceMappings = mappingsForStage.filter((m) => m.sourceId === selectedSourceId);
  const linkedTargetIds = new Set(selectedSourceMappings.map((m) => m.targetId));
  const selectedTargets = [...selectedTargetIds].map((id) => targetById.get(id)).filter(Boolean);
  const newTargetCount = selectedTargets.filter((t) => !linkedTargetIds.has(t.id)).length;
  const canEstablish = Boolean(selectedSource && selectedTargets.length > 0);

  const groups = groupBySource(mappingsForStage, sourceItems, targetById);

  return (
    <div className="stm-controls">
      {coverage && (
        <div className="stm-coverage" aria-label={`${stage.label} coverage`}>
          <div className="stm-coverage__stat">
            <span className="stm-coverage__value">{coverage.total}</span>
            <span className="stm-coverage__label">{isProposalStage ? 'SMART Goals' : 'Sources'}</span>
          </div>
          <div className="stm-coverage__stat stm-coverage__stat--ok">
            <span className="stm-coverage__value">{coverage.mapped}</span>
            <span className="stm-coverage__label">Mapped</span>
          </div>
          <div className={`stm-coverage__stat ${coverage.unmapped > 0 ? 'stm-coverage__stat--warn' : ''}`}>
            <span className="stm-coverage__value">{coverage.unmapped}</span>
            <span className="stm-coverage__label">Unmapped</span>
          </div>
          <div className="stm-coverage__stat">
            <span className="stm-coverage__value">{coverage.percent}%</span>
            <span className="stm-coverage__label">{isProposalStage ? 'Goal coverage' : 'Coverage'}</span>
          </div>
          {isProposalStage && coverage.total > 0 && (
            <p className={`stm-coverage__note ${coverage.percent >= 90 ? 'stm-coverage__note--ok' : ''}`}>
              {coverage.percent >= 90 ? 'Meets' : 'Below'} the 90% SMART goal coverage target.
            </p>
          )}
        </div>
      )}

      <div className="stm-controls__preview">
        <div className={`stm-controls__slot ${selectedSource ? 'stm-controls__slot--filled' : ''}`}>
          <span className="stm-controls__slot-label">{isProposalStage ? 'Source · SMART Goal' : 'Source'}</span>
          {selectedSource ? (
            <>
              <span className="stm-controls__slot-value"><ItemLine item={selectedSource} /></span>
              {selectedSourceMappings.length === 0 ? (
                <span className="stm-controls__slot-note">Not mapped yet — a potential traceability gap.</span>
              ) : (
                <ul className="stm-controls__links">
                  {selectedSourceMappings.map((m) => (
                    <li key={m.id}>
                      <span className="stm-controls__link-arrow" aria-hidden="true">→</span>
                      <ItemLine item={targetById.get(m.targetId)} fallback={missingLabel} />
                      <button className="stm-mini-btn" onClick={() => onRemoveMapping(m.id)}>Remove</button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          ) : (
            <span className="stm-controls__slot-value">
              Select a {isProposalStage ? 'SMART goal' : 'source component'}
            </span>
          )}
        </div>
        <span className="stm-controls__arrow">to</span>
        <div className={`stm-controls__slot ${selectedTargets.length > 0 ? 'stm-controls__slot--filled' : ''}`}>
          <span className="stm-controls__slot-label stm-controls__slot-label--row">
            Targets{selectedTargets.length > 0 ? ` (${selectedTargets.length} selected)` : ''}
            {selectedTargets.length > 0 && onClearTargets && (
              <button className="stm-mini-btn" onClick={onClearTargets}>Clear</button>
            )}
          </span>
          {selectedTargets.length > 0 ? (
            <ul className="stm-controls__links">
              {selectedTargets.map((t) => (
                <li key={t.id}>
                  <span className="stm-controls__link-arrow" aria-hidden="true">→</span>
                  <ItemLine item={t} />
                  {linkedTargetIds.has(t.id) && <span className="stm-controls__slot-note">already mapped</span>}
                </li>
              ))}
            </ul>
          ) : (
            <span className="stm-controls__slot-value">Select one or more target components</span>
          )}
        </div>
      </div>

      <button className="btn btn--primary stm-controls__establish" disabled={!canEstablish} onClick={onEstablish}>
        {newTargetCount > 1 ? `Establish ${newTargetCount} Mappings` : 'Establish Mapping'}
      </button>

      <div className="stm-controls__list-header">{stage.label} mappings ({mappingsForStage.length})</div>
      {groups.length === 0 ? (
        <p className="tm-muted stm-controls__empty">No mappings yet for this stage.</p>
      ) : (
        <ul className="stm-controls__list">
          {groups.map(([sourceId, rows]) => (
            <li
              key={sourceId}
              className={`stm-controls__group ${sourceId === selectedSourceId ? 'stm-controls__group--selected' : ''}`}
            >
              <ItemLine item={sourceById.get(sourceId)} fallback={missingLabel} />
              <ul className="stm-controls__links">
                {rows.map((m) => (
                  <li key={m.id}>
                    <span className="stm-controls__link-arrow" aria-hidden="true">→</span>
                    <ItemLine item={targetById.get(m.targetId)} fallback={missingLabel} />
                    <button className="stm-mini-btn" onClick={() => onRemoveMapping(m.id)}>Remove</button>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default MappingControls;
