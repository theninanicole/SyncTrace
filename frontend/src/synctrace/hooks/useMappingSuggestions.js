import { useCallback, useEffect, useRef, useState } from 'react';
import {
  acceptAllSuggestions, decideSuggestion, discardSuggestions, generateSuggestions, getSuggestions,
} from '../api';
import { API_BASE_URL } from '../../api';
import { notifyMappingsChanged } from '../utils/mappingEvents';

function progressStream(sessionId, setProgress) {
  const stream = new EventSource(`${API_BASE_URL}/ai/progress/${sessionId}`);
  stream.addEventListener('progress', (event) => {
    try { setProgress(JSON.parse(event.data)); } catch { /* malformed progress is non-fatal */ }
  });
  stream.addEventListener('done', () => stream.close());
  stream.addEventListener('error', () => stream.close());
  return stream;
}

export function useMappingSuggestions(teamCode, stage, onAccepted) {
  const [suggestions, setSuggestions] = useState([]);
  const [state, setState] = useState('idle');
  const [progress, setProgress] = useState(null);
  const [error, setError] = useState(null);
  const streamRef = useRef(null);

  const load = useCallback(async () => {
    if (!teamCode || !stage) return;
    setSuggestions(await getSuggestions(teamCode, stage));
  }, [teamCode, stage]);

  useEffect(() => {
    load().catch((err) => setError(err.message));
    return () => streamRef.current?.close();
  }, [load]);

  const generate = useCallback(async () => {
    const sessionId = `mapping-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    setState('generating'); setError(null); setProgress(null);
    streamRef.current = progressStream(sessionId, setProgress);
    try {
      const rows = await generateSuggestions(teamCode, stage, sessionId);
      setSuggestions(rows); setState('ready'); return rows;
    } catch (err) {
      setState(err.code === 'AI_UNAVAILABLE' ? 'unavailable' : 'error');
      setError(err.message); throw err;
    } finally { streamRef.current?.close(); }
  }, [teamCode, stage]);

  const decide = useCallback(async (id, decision, targetIds) => {
    const result = await decideSuggestion(teamCode, id, decision, targetIds);
    setSuggestions((rows) => rows.filter((row) => row.id !== id));
    if (decision !== 'REJECT') { await onAccepted?.(); notifyMappingsChanged(teamCode); }
    return result;
  }, [teamCode, onAccepted]);

  const acceptAll = useCallback(async () => {
    const result = await acceptAllSuggestions(teamCode, stage);
    await onAccepted?.(); notifyMappingsChanged(teamCode); await load(); return result;
  }, [teamCode, stage, load, onAccepted]);

  const discard = useCallback(async () => {
    if (teamCode && stage) await discardSuggestions(teamCode, stage);
    setSuggestions([]);
  }, [teamCode, stage]);

  return { suggestions, state, progress, error, generate, decide, acceptAll, discard, reload: load };
}
