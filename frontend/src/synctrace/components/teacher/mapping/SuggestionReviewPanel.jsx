import { useState } from 'react';

function label(items, id) {
  const item = items.find((value) => value.id === id);
  return item?.code || item?.name || item?.description || `#${id}`;
}

export default function SuggestionReviewPanel({
  suggestions, sourceItems, targetItems, onDecide, onAcceptAll, onClose, state, progress, error,
  generation,
}) {
  const [editing, setEditing] = useState(null);
  const [targets, setTargets] = useState([]);
  const [targetSearch, setTargetSearch] = useState('');
  const toggle = (id) => setTargets((current) => current.includes(id) ? current.filter((x) => x !== id) : [...current, id]);
  const visibleTargets = targetItems.filter((target) => label(targetItems, target.id).toLowerCase().includes(targetSearch.toLowerCase()));
  const empty = state !== 'generating' && suggestions.length === 0;
  const unavailable = state === 'unavailable';
  return (
    <aside className="tm-suggestion-panel">
      <div className="tm-suggestion-panel__header"><h3>AI draft mappings</h3><button type="button" onClick={onClose}>Close</button></div>
      {state === 'generating' && <p>Generating suggestions… {progress?.percent || 0}%</p>}
      {generation?.partial && (
        <p role="status">
          Showing suggestions for {generation.sourcesProcessed} of {generation.sourcesTotal} items.
          {' '}Run Suggest Mappings again or map the rest manually.
        </p>
      )}
      {unavailable && <p>AI is unavailable. You can continue mapping manually.</p>}
      {error && !unavailable && <p role="alert">{error}</p>}
      {empty && !unavailable && <p>No likely matches were found.</p>}
      {suggestions.length > 0 && <button type="button" onClick={onAcceptAll}>Accept all</button>}
      <ul>
        {suggestions.map((suggestion) => (
          <li key={suggestion.id}>
            <strong>{label(sourceItems, suggestion.sourceId)} → {label(targetItems, suggestion.targetComponentId)}</strong>
            <span> ({Math.round(suggestion.score * 100)}%)</span>
            <p>{suggestion.reason}</p>
            {editing === suggestion.id ? (
              <div className="tm-suggestion-panel__edit">
                <input
                  type="search"
                  placeholder="Search targets"
                  value={targetSearch}
                  onChange={(event) => setTargetSearch(event.target.value)}
                />
                {visibleTargets.map((target) => (
                <label key={target.id}><input type="checkbox" checked={targets.includes(target.id)} onChange={() => toggle(target.id)} /> {label(targetItems, target.id)}</label>
              ))}
                <button type="button" disabled={!targets.length} onClick={() => { onDecide(suggestion.id, 'EDIT', targets); setEditing(null); }}>Accept edit</button>
              </div>
            ) : <><button type="button" onClick={() => onDecide(suggestion.id, 'ACCEPT')}>Accept</button><button type="button" onClick={() => { setEditing(suggestion.id); setTargets([suggestion.targetComponentId]); setTargetSearch(''); }}>Edit</button><button type="button" onClick={() => onDecide(suggestion.id, 'REJECT')}>Reject</button></>}
          </li>
        ))}
      </ul>
    </aside>
  );
}
