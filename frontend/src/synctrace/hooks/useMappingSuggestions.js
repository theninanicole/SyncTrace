import { useCallback, useEffect, useRef, useState } from 'react';
import {
  acceptAllSuggestions, decideSuggestion, discardSuggestions, generateSuggestions, getSuggestions,
} from '../api';
import { notifyMappingsChanged } from '../utils/mappingEvents';
import { openProgressStream } from './useStagedTraceability';

export function useMappingSuggestions(teamCode, stage, onAccepted, showToast) {
  const [suggestions, setSuggestions] = useState([]);
  const [state, setState] = useState('idle');
  const [progress, setProgress] = useState(null);
  const [generation, setGeneration] = useState(null);
  const [error, setError] = useState(null);
  const streamRef = useRef(null);

  const load = useCallback(async () => {
    if (!teamCode || !stage) return;
    setGeneration(null);
    setSuggestions(await getSuggestions(teamCode, stage));
  }, [teamCode, stage]);

  useEffect(() => {
    load().catch((err) => setError(err.message));
    return () => {
      streamRef.current?.close();
      if (teamCode && stage) {
        discardSuggestions(teamCode, stage).catch(() => {});
      }
    };
  }, [load, teamCode, stage]);

  const generate = useCallback(async () => {
    const sessionId = `mapping-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    setState('generating'); setError(null); setProgress(null); setGeneration(null);
    try {
      streamRef.current = await openProgressStream(sessionId, setProgress);
      const result = await generateSuggestions(teamCode, stage, sessionId);
      setGeneration(result);
      setSuggestions(result.suggestions || []); setState('ready'); return result;
    } catch (err) {
      setState(err.code === 'AI_UNAVAILABLE' ? 'unavailable' : 'error');
      setError(err.message); throw err;
    } finally { streamRef.current?.close(); }
  }, [teamCode, stage]);

  const decide = useCallback(async (id, decision, targetIds) => {
    try {
      const result = await decideSuggestion(teamCode, id, decision, targetIds);
      setSuggestions((rows) => rows.filter((row) => row.id !== id));
      if (decision !== 'REJECT') { await onAccepted?.(); notifyMappingsChanged(teamCode); }
      return result;
    } catch (err) {
      showToast?.(err.message || 'Could not decide this suggestion.', 'error');
      return null;
    }
  }, [teamCode, onAccepted, showToast]);

  const acceptAll = useCallback(async () => {
    try {
      const result = await acceptAllSuggestions(teamCode, stage);
      await onAccepted?.(); notifyMappingsChanged(teamCode); await load(); return result;
    } catch (err) {
      showToast?.(err.message || 'Could not accept all suggestions.', 'error');
      return null;
    }
  }, [teamCode, stage, load, onAccepted, showToast]);

  const discard = useCallback(async () => {
    if (teamCode && stage) await discardSuggestions(teamCode, stage);
    setSuggestions([]);
  }, [teamCode, stage]);

  return {
    suggestions, state, progress, error, generation, generate, decide, acceptAll, discard, reload: load,
  };
}
