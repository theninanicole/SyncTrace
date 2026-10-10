import { nextGoalDraftKey } from '../../../utils/smartGoalsSection';
import '../../pages/teacher/TraceabilityMappingPage.css';

/**
 * Editable General / Specific objective tree, used to review extracted SMART goals before
 * saving.
 * draft: rows from toEditableGoalDraft(); onChange receives the next draft.
 */
function SmartGoalTreeEditor({ draft, onChange, disabled = false, emptyMessage }) {
  const updateGoal = (key, changes) =>
    onChange(draft.map((g) => (g.key === key ? { ...g, ...changes } : g)));
  const removeGoal = (key) => onChange(draft.filter((g) => g.key !== key));
  const addGeneral = () =>
    onChange([...draft, { key: nextGoalDraftKey(), goalKind: 'GENERAL', description: '', children: [] }]);
  const updateChildren = (goalKey, update) =>
    onChange(draft.map((g) => (g.key === goalKey ? { ...g, children: update(g.children) } : g)));

  return (
    <div className="stm-goal-review">
      {draft.length === 0 && emptyMessage && <p className="tm-muted">{emptyMessage}</p>}
      {draft.map((goal, index) => {
        const isGeneral = goal.goalKind === 'GENERAL';
        return (
          <div key={goal.key} className="stm-goal-review__goal">
            <div className="stm-goal-review__row">
              <span className="tm-goal-kind tm-goal-kind--general">G{index + 1}</span>
              <span className="stm-goal-review__kind">
                {isGeneral ? 'General objective' : 'Specific objective (no general objective)'}
              </span>
              <button type="button" className="stm-mini-btn" onClick={() => removeGoal(goal.key)} disabled={disabled}>
                Remove
              </button>
            </div>
            <textarea
              className="stm-goal-editor__input"
              rows={2}
              value={goal.description}
              placeholder={isGeneral ? 'General objective' : 'Specific objective'}
              disabled={disabled}
              onChange={(e) => updateGoal(goal.key, { description: e.target.value })}
            />
            {goal.children.length > 0 && (
              <ul className="stm-goal-review__children">
                {goal.children.map((child) => (
                  <li key={child.key}>
                    <textarea
                      className="stm-goal-editor__input"
                      rows={2}
                      value={child.description}
                      placeholder="Specific objective"
                      disabled={disabled}
                      onChange={(e) => updateChildren(goal.key, (children) =>
                        children.map((c) => (c.key === child.key ? { ...c, description: e.target.value } : c)))}
                    />
                    <button
                      type="button"
                      className="stm-mini-btn"
                      onClick={() => updateChildren(goal.key, (children) => children.filter((c) => c.key !== child.key))}
                      disabled={disabled}
                    >
                      Remove
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {isGeneral && (
              <button
                type="button"
                className="stm-mini-btn stm-goal-cluster__add"
                onClick={() => updateChildren(goal.key, (children) =>
                  [...children, { key: nextGoalDraftKey(), description: '' }])}
                disabled={disabled}
              >
                Add specific objective
              </button>
            )}
          </div>
        );
      })}
      <button type="button" className="btn btn--soft stm-goal-review__add" onClick={addGeneral} disabled={disabled}>
        Add general objective
      </button>
    </div>
  );
}

export default SmartGoalTreeEditor;
