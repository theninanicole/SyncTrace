import { groupGoalsIntoClusters } from '../../../constants';

function SmartGoalPanel({
  smartGoalsExtracted,
  smartGoals,
  loading,
  extracting,
  selectedId,
  linkedIds,
  mappedCounts,
  onSelect,
  onExtractClick,
}) {
  const clusters = groupGoalsIntoClusters(smartGoals);

  if (extracting) {
    return (
      <div className="stm-column-empty">
        <p>Extracting SMART goals from the evaluated proposal…</p>
      </div>
    );
  }

  if (loading) {
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
        <button className="btn btn--primary" onClick={onExtractClick}>
          Extract SMART Goals
        </button>
      </div>
    );
  }

  if (clusters.length === 0) {
    return (
      <div className="stm-column-empty">
        <p>The proposal did not yield any SMART goals.</p>
        <button className="btn btn--soft" onClick={onExtractClick}>
          Re-extract SMART Goals
        </button>
      </div>
    );
  }

  return (
    <div className="stm-goal-tree">
      {clusters.map((cluster) => {
        const count = mappedCounts?.[cluster.id] || 0;
        const isSelected = selectedId === cluster.id;
        const isLinked = linkedIds?.has(cluster.id);
        return (
          <div key={cluster.id} className="stm-goal-cluster">
            <button
              type="button"
              className={[
                'stm-goal-node',
                isSelected ? 'stm-goal-node--selected' : '',
                isLinked ? 'stm-goal-node--linked' : '',
              ].filter(Boolean).join(' ')}
              onClick={() => onSelect(cluster.id)}
            >
              <span className={`tm-goal-kind tm-goal-kind--${cluster.primary.goalKind.toLowerCase()}`}>
                {cluster.primary.goalKind === 'GENERAL' ? 'GEN' : 'SPEC'}
              </span>
              <span className="stm-goal-node__text">{cluster.primary.description}</span>
              {count > 0 && <span className="stm-mapped-count">{count}</span>}
            </button>
            {cluster.children.length > 0 && (
              <ul className="tm-goal-item__children tm-goal-summary__children">
                {cluster.children.map((child) => (
                  <li key={child.id}>
                    <span className="tm-goal-kind tm-goal-kind--specific">SPEC</span>
                    {child.description}
                  </li>
                ))}
              </ul>
            )}
          </div>
        );
      })}
    </div>
  );
}

export default SmartGoalPanel;
