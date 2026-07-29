import { useState } from 'react';

function NewGoalForm({ onCreate, onCancel, defaultTeamCode = '' }) {
  const [description, setDescription] = useState('');
  const [teamCode, setTeamCode] = useState(defaultTeamCode);

  function handleSubmit() {
    if (!description.trim()) return;
    onCreate(description.trim(), {
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
