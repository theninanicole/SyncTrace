/** SyncTrace client routes (History API — no react-router). */

export const SYNCTRACE_BASE = '/synctrace';

/** Internal view key → URL path segment */
export const VIEW_TO_SEGMENT = {
  overview: 'overview',
  traceability: 'tmapping',
  'traceability-results': 'results',
  source: 'source',
  results: 'group', // group/:teamCode
};

/** URL path segment → internal view key */
export const SEGMENT_TO_VIEW = {
  overview: 'overview',
  tmapping: 'traceability',
  results: 'traceability-results',
  source: 'source',
  group: 'results',
};

export function isSyncTracePath(pathname = window.location.pathname) {
  const p = pathname.replace(/\/+$/, '') || '/';
  return p === SYNCTRACE_BASE || p.startsWith(`${SYNCTRACE_BASE}/`);
}

/**
 * Parse pathname (+ search) into SyncTrace location state.
 * @returns {{ view: string, teamCode: string|null, focusGoalId: number|null, focusStep: string|null, focusDocType: string|null } | null}
 */
export function pathToView(pathname = window.location.pathname, search = window.location.search) {
  const raw = pathname.replace(/\/+$/, '') || '/';
  if (!isSyncTracePath(raw)) return null;

  const rest = raw.slice(SYNCTRACE_BASE.length).replace(/^\//, '');
  if (!rest) {
    return {
      view: 'overview',
      teamCode: null,
      ...parseFocusQuery(search),
      redirectTo: `${SYNCTRACE_BASE}/overview`,
    };
  }

  const parts = rest.split('/').filter(Boolean);
  const segment = parts[0];
  const view = SEGMENT_TO_VIEW[segment];

  if (!view) {
    return {
      view: 'overview',
      teamCode: null,
      ...parseFocusQuery(search),
      redirectTo: `${SYNCTRACE_BASE}/overview`,
    };
  }

  let teamCode = null;
  if (view === 'results') {
    teamCode = parts[1] ? decodeURIComponent(parts[1]) : null;
    if (!teamCode) {
      return {
        view: 'overview',
        teamCode: null,
        ...parseFocusQuery(search),
        redirectTo: `${SYNCTRACE_BASE}/overview`,
      };
    }
  }

  return {
    view,
    teamCode,
    ...parseFocusQuery(search),
  };
}

export function parseFocusQuery(search = '') {
  const params = new URLSearchParams(typeof search === 'string' ? search : '');
  const goalRaw = params.get('goalId');
  const goalNum = goalRaw != null && goalRaw !== '' ? Number(goalRaw) : null;
  return {
    focusGoalId: Number.isFinite(goalNum) ? goalNum : (goalRaw || null),
    focusStep: params.get('step') || null,
    focusDocType: params.get('docType') || null,
  };
}

/**
 * Build a SyncTrace path for an internal view key.
 * @param {string} view
 * @param {{ teamCode?: string, focusGoalId?: string|number, focusStep?: string, focusDocType?: string }} [options]
 */
export function buildSyncTracePath(view, options = {}) {
  if (view === 'results') {
    const code = options.teamCode;
    if (!code) return `${SYNCTRACE_BASE}/overview`;
    return `${SYNCTRACE_BASE}/group/${encodeURIComponent(code)}`;
  }

  const segment = VIEW_TO_SEGMENT[view] || 'overview';
  let path = `${SYNCTRACE_BASE}/${segment}`;

  const params = new URLSearchParams();
  if (options.focusGoalId != null && options.focusGoalId !== '') {
    params.set('goalId', String(options.focusGoalId));
  }
  if (options.focusStep) params.set('step', options.focusStep);
  if (options.focusDocType) params.set('docType', options.focusDocType);

  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

export function navigatePath(path, { replace = false } = {}) {
  const method = replace ? 'replaceState' : 'pushState';
  window.history[method](null, '', path);
  window.dispatchEvent(new PopStateEvent('popstate'));
}
