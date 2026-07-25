import { useState } from 'react';
import { GOAL_KINDS } from '../../constants';

function NewGoalForm({ onCreate, onCancel, generalGoals = [], defaultTeamCode = '' }) {
  const [description, setDescription] = useState('');
  const [goalKind, setGoalKind] = useState('SPECIFIC');
  const [parentGoalId, setParentGoalId] = useState('');
  const [teamCode, setTeamCode] = useState(defaultTeamCode);

  function handleSubmit() {
    if (!description.trim()) return;
    onCreate(description.trim(), {
      goalKind,
      parentGoalId: goalKind === 'SPECIFIC' && parentGoalId ? Number(parentGoalId) : null,
      teamCode: teamCode.trim() || null,
    });
  }

  return (
    <div className="tm-new-goal" style={{ maxWidth: '320px', width: '100%', boxSizing: 'border-box', overflow: 'hidden' }}>
      <textarea
        className="pw-textarea"
        placeholder="e.g. Enable automated submission management via Google Sheets integration by end of development"
        rows={3}
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        style={{ width: '100%', boxSizing: 'border-box' }}
      />

      <label className="tm-field-label">Goal kind</label>
      <select
        className="tm-select"
        value={goalKind}
        onChange={(e) => {
          setGoalKind(e.target.value);
          if (e.target.value === 'GENERAL') setParentGoalId('');
        }}
        style={{ width: '100%', boxSizing: 'border-box' }}
      >
        {GOAL_KINDS.map((k) => (
          <option key={k.value} value={k.value}>{k.label}</option>
        ))}
      </select>

      {goalKind === 'SPECIFIC' && (
        <>
          <label className="tm-field-label">Parent general objective (optional)</label>
          <select
            className="tm-select"
            value={parentGoalId}
            onChange={(e) => setParentGoalId(e.target.value)}
            style={{ width: '100%', boxSizing: 'border-box' }}
          >
            <option value="">None</option>
            {generalGoals.map((g) => (
              <option key={g.id} value={g.id}>{g.description}</option>
            ))}
          </select>
        </>
      )}

      <label className="tm-field-label">Team code (optional)</label>
      <input
        className="tm-input"
        placeholder="e.g. 2526-sem2-it332-08"
        value={teamCode}
        onChange={(e) => setTeamCode(e.target.value)}
        style={{ width: '100%', boxSizing: 'border-box', minWidth: 0, textOverflow: 'ellipsis' }}
      />

      <div className="pw-action-row">
        <button className="pw-btn pw-btn--ghost" onClick={onCancel}>Cancel</button>
        <button
          className="pw-btn pw-btn--primary"
          disabled={!description.trim()}
          onClick={handleSubmit}
        >
          Create Goal
        </button>
      </div>
    </div>
  );
}

export default NewGoalForm;