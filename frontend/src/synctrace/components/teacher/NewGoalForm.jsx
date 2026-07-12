import { useState } from 'react';

function NewGoalForm({ onCreate, onCancel }) {
  const [description, setDescription] = useState('');
  return (
    <div className="tm-new-goal">
      <textarea
        className="pw-textarea"
        placeholder="e.g. Enable automated submission management via Google Sheets integration by end of development"
        rows={3}
        value={description}
        onChange={(e) => setDescription(e.target.value)}
      />
      <div className="pw-action-row">
        <button className="pw-btn pw-btn--ghost" onClick={onCancel}>Cancel</button>
        <button
          className="pw-btn pw-btn--primary"
          disabled={!description.trim()}
          onClick={() => onCreate(description.trim())}
        >
          Create Goal
        </button>
      </div>
    </div>
  );
}

export default NewGoalForm;
