import { useEffect } from 'react';
import { sendStageHeartbeat } from '../api';

// How often an open, visible mapping stage reports in (metric M4). The server counts the time
// between two beats as active only when they are at most 2 minutes apart.
const HEARTBEAT_INTERVAL_MS = 30_000;

/**
 * Invisible M4 heartbeat: while `stage` is open for `teamCode` and the tab is visible, tell the
 * server every 30 s; send a final "hidden" beat when the tab is hidden, the stage changes, or the
 * page closes, so hidden time is never counted as active.
 */
export function useStageHeartbeat(teamCode, stage) {
  useEffect(() => {
    if (!teamCode || !stage) return undefined;

    let timer = null;
    const beat = (state) => sendStageHeartbeat(teamCode, stage, state);

    const start = () => {
      if (timer) return;
      beat('visible');
      timer = setInterval(() => beat('visible'), HEARTBEAT_INTERVAL_MS);
    };
    const stop = () => {
      if (!timer) return;
      clearInterval(timer);
      timer = null;
      beat('hidden');
    };
    const onVisibility = () => (document.visibilityState === 'visible' ? start() : stop());

    if (document.visibilityState === 'visible') start();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', stop);

    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('pagehide', stop);
      stop();
    };
  }, [teamCode, stage]);
}
