import { useEffect, useRef } from 'react';

// Save Mapping announces itself so every traceability view reloads: a window event for
// views in this tab, a BroadcastChannel for other tabs. Saves made by other users are
// picked up when the window regains focus and, where enabled, by periodic polling.
const EVENT_NAME = 'synctrace:mappings-changed';
const CHANNEL_NAME = 'synctrace-mappings';

export function notifyMappingsChanged(teamCode) {
  const detail = { teamCode: teamCode || null };
  window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail }));
  try {
    const channel = new BroadcastChannel(CHANNEL_NAME);
    channel.postMessage(detail);
    channel.close();
  } catch {
    // BroadcastChannel unsupported — other tabs still refresh on focus.
  }
}

/**
 * Calls onChange when mappings for teamCode (or any team, when teamCode is empty) may
 * have changed. pollMs > 0 also reloads on an interval while the page is visible.
 */
export function useMappingsChangedRefresh(teamCode, onChange, { pollMs = 0 } = {}) {
  const callbackRef = useRef(onChange);
  useEffect(() => {
    callbackRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    const matchesTeam = (changedTeam) =>
      !teamCode || !changedTeam || changedTeam.toUpperCase() === teamCode.toUpperCase();
    const refresh = () => callbackRef.current?.();

    const onEvent = (e) => { if (matchesTeam(e.detail?.teamCode)) refresh(); };
    const onVisible = () => { if (document.visibilityState === 'visible') refresh(); };

    window.addEventListener(EVENT_NAME, onEvent);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', onVisible);

    let channel = null;
    try {
      channel = new BroadcastChannel(CHANNEL_NAME);
      channel.onmessage = (e) => { if (matchesTeam(e.data?.teamCode)) refresh(); };
    } catch {
      channel = null;
    }

    const interval = pollMs > 0
      ? setInterval(() => { if (document.visibilityState === 'visible') refresh(); }, pollMs)
      : null;

    return () => {
      window.removeEventListener(EVENT_NAME, onEvent);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', onVisible);
      channel?.close();
      if (interval) clearInterval(interval);
    };
  }, [teamCode, pollMs]);
}
