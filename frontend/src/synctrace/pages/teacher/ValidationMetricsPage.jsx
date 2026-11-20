import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown, Plus, RefreshCw, Trash2 } from 'lucide-react';
import PanelHeader from '../../../components/common/PanelHeader';
import InfoHint from '../../../components/common/InfoHint';
import AppModal from '../../../components/common/AppModal';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import {
  createValidationSession,
  deleteValidationSession,
  exportValidationMetrics,
  getValidationSessions,
  getValidationSummary,
} from '../../api';
import { mappingStageLabel } from '../../constants';
import TeamSelect from '../../components/common/TeamSelect';
import './TraceabilityMappingPage.css';
import './OverviewPage.css';
import './ValidationMetricsPage.css';

// M4 stage codes → mapping stage keys, for the stage labels used everywhere else.
const STAGE_KEYS = {
  proposal_srs: 'PROPOSAL_SRS',
  srs_sdd: 'SRS_SDD',
  srs_std: 'SRS_STD',
  srs_spmp: 'SRS_SPMP',
  sdd_impl: 'SDD_IMPLEMENTATION',
};

const EXPORT_FORMATS = [
  { key: 'zip', label: 'CSV (zip)' },
  { key: 'xlsx', label: 'Excel (.xlsx)' },
];

const PERIODS = [
  { key: 'all', label: 'All time' },
  { key: 'session', label: 'Session' },
  { key: 'range', label: 'Date range' },
];

const METRIC_HINTS = {
  M1: 'Server time from the extract request until the draft goals are ready, including the Drive download, AI call and retries.',
  M2: 'Server time to read an IEEE Docs Evaluator report and save its components.',
  M3: 'Server time per repository, from the ingest request until its status is saved, including every GitHub call.',
  M4: 'Active time per stage session (tab visible, no gap over 2 minutes), from opening the stage to Save Mapping.',
  M5: 'Accepted + edited ÷ (accepted + edited + rejected). Pending and system-rejected suggestions are shown but not in the rate.',
  M6: 'Gap detection + recommendations of one "Run analysis", from the first step’s start to the last step’s end.',
  M7: 'Detected gaps that received both a diagnostic explanation and a recommendation (blank text does not count).',
  M8: 'Server time to build and render the audit report, per export format.',
};

function formatDuration(ms) {
  if (ms === null || ms === undefined) return '—';
  if (ms < 1_000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms % 60_000) / 1000);
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
}

function formatTarget(metric) {
  if (metric.targetUnit === '%') return `${metric.metric === 'M7' ? '' : '≥ '}${metric.target}%`;
  if (metric.target >= 60_000 && metric.target % 60_000 === 0) return `≤ ${metric.target / 60_000} min`;
  if (metric.target % 1_000 === 0) return `≤ ${metric.target / 1_000} s`;
  return `≤ ${formatDuration(metric.target)}`;
}

function formatPct(value) {
  return value === null || value === undefined ? '—' : `${value}%`;
}

function ResultBadge({ met }) {
  if (met === true) return <span className="status-chip status-chip--sent">Met</span>;
  if (met === false) return <span className="status-chip status-chip--critical">Not met</span>;
  return <span className="status-chip vm-chip--none">No data</span>;
}

function metricRow(metric) {
  switch (metric.metric) {
    case 'M5':
      return {
        count: metric.count,
        median: '—',
        max: '—',
        pct: formatPct(metric.acceptanceRate),
        detail: `${metric.accepted} accepted · ${metric.edited} edited · ${metric.rejected} rejected · ${metric.pending} pending`
          + (metric.systemRejected ? ` · ${metric.systemRejected} stale` : ''),
      };
    case 'M7':
      return {
        count: metric.count,
        median: '—',
        max: '—',
        pct: formatPct(metric.coveragePct),
        detail: metric.gapsCounted ? `${metric.gapsWithBoth} of ${metric.gapsCounted} gaps explained` : 'No gaps detected yet',
      };
    case 'M4':
      return {
        count: metric.count,
        median: formatDuration(metric.medianMs),
        max: formatDuration(metric.maxMs),
        pct: formatPct(metric.pctWithinTarget),
        detail: `Active time · median wall-clock ${formatDuration(metric.medianWallClockMs)}`
          + (metric.openSessions ? ` · ${metric.openSessions} not finalized` : ''),
      };
    default: {
      const problems = [];
      if (metric.failed) problems.push(`${metric.failed} failed`);
      if (metric.timedOut) problems.push(`${metric.timedOut} timed out`);
      return {
        count: metric.count,
        median: formatDuration(metric.medianMs),
        max: formatDuration(metric.maxMs),
        pct: formatPct(metric.pctWithinTarget),
        detail: problems.join(' · '),
      };
    }
  }
}

function toIsoStart(date) {
  return date ? new Date(`${date}T00:00:00`).toISOString() : null;
}

function toIsoEndExclusive(date) {
  if (!date) return null;
  const end = new Date(`${date}T00:00:00`);
  end.setDate(end.getDate() + 1);
  return end.toISOString();
}

function sessionLabel(session) {
  const start = new Date(session.startsAt);
  const end = new Date(session.endsAt);
  const time = (d) => d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  return `${session.name} (${start.toLocaleDateString()} ${time(start)}–${time(end)})`;
}

const isMeasured = (metric) => metric?.targetMet !== null && metric?.targetMet !== undefined;

/** Per metric (and per M4 stage): how many teams met it, out of the teams with data for it. */
function teamsMetByMetric(teams) {
  const tally = {};
  const add = (key, metric) => {
    if (!isMeasured(metric)) return;
    tally[key] = tally[key] || { met: 0, measured: 0 };
    tally[key].measured += 1;
    if (metric.targetMet) tally[key].met += 1;
  };
  teams.forEach((team) => {
    team.metrics.forEach((metric) => add(metric.metric, metric));
    team.m4Stages.forEach((stage) => add(`M4:${stage.stage}`, stage));
  });
  return tally;
}

function formatTeamsMet(tally) {
  return tally ? `${tally.met} of ${tally.measured}` : '—';
}

/** @param teamsMet when set, the card is the all-teams overview and shows a "Teams met" column */
function TeamCard({ team, teamsMet = null, teamCount = 0 }) {
  const overview = teamsMet !== null;
  const met = team.metrics.filter((m) => m.targetMet === true).length;
  const measured = team.metrics.filter(isMeasured).length;

  return (
    <section className={`card vm-team-card${overview ? ' vm-team-card--overview' : ''}`}>
      <header className="vm-team-card__header">
        <div>
          <h3 className="vm-team-card__title">{overview ? 'All teams overview' : `Team ${team.teamId}`}</h3>
          {overview && (
            <p className="tm-muted vm-overview-note">
              {teamCount} team{teamCount === 1 ? '' : 's'} · every run pooled together, so medians and rates are over all teams&apos; runs.
            </p>
          )}
        </div>
        <span className="tm-badge">{measured === 0 ? 'No data yet' : `${met} of ${measured} measured targets met`}</span>
      </header>

      <div className="vm-table-wrap">
        <table className="app-table vm-table">
          <thead>
            <tr>
              <th>Metric</th>
              <th>Target</th>
              <th className="vm-num">Count</th>
              <th className="vm-num">Median</th>
              <th className="vm-num">Max</th>
              <th className="vm-num">% within target</th>
              {overview && <th className="vm-num">Teams met</th>}
              <th>Result</th>
            </tr>
          </thead>
          <tbody>
            {team.metrics.map((metric) => {
              const row = metricRow(metric);
              return (
                <tr key={metric.metric}>
                  <td>
                    <span className="vm-metric-code">{metric.metric}</span> {metric.label}
                    <InfoHint text={METRIC_HINTS[metric.metric]} />
                    {row.detail && <div className="vm-detail">{row.detail}</div>}
                  </td>
                  <td>{formatTarget(metric)}</td>
                  <td className="vm-num">{row.count}</td>
                  <td className="vm-num">{row.median}</td>
                  <td className="vm-num">{row.max}</td>
                  <td className="vm-num">{row.pct}</td>
                  {overview && <td className="vm-num">{formatTeamsMet(teamsMet[metric.metric])}</td>}
                  <td><ResultBadge met={metric.targetMet} /></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="vm-stages">
        <span className="tm-field-label">M4 by stage (active time)</span>
        <div className="vm-stage-list">
          {team.m4Stages.map((stage) => (
            <div key={stage.stage} className="vm-stage">
              <span className="vm-stage__label">{mappingStageLabel(STAGE_KEYS[stage.stage])}</span>
              <span className="vm-stage__value">
                {stage.count === 0 ? (stage.openSessions ? 'In progress' : '—') : formatDuration(stage.medianMs)}
              </span>
              {overview && (
                <span className="tm-muted vm-stage__teams">{formatTeamsMet(teamsMet[`M4:${stage.stage}`])} teams</span>
              )}
              <ResultBadge met={stage.targetMet} />
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function NewSessionModal({ isOpen, onClose, onCreate, selectedTeams }) {
  const today = new Date().toISOString().slice(0, 10);
  const [name, setName] = useState('');
  const [date, setDate] = useState(today);
  const [start, setStart] = useState('09:00');
  const [end, setEnd] = useState('11:00');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  async function submit() {
    setError('');
    const startsAt = new Date(`${date}T${start}`);
    const endsAt = new Date(`${date}T${end}`);
    if (!(endsAt > startsAt)) {
      setError('The session must end after it starts.');
      return;
    }
    setSaving(true);
    try {
      const fallbackName = `Validation session — ${startsAt.toLocaleDateString([], { month: 'short', day: 'numeric' })}, `
        + `${startsAt.toLocaleTimeString([], { hour: 'numeric' })}–${endsAt.toLocaleTimeString([], { hour: 'numeric' })}`;
      await onCreate({
        name: name.trim() || fallbackName,
        sessionDate: date,
        startsAt: startsAt.toISOString(),
        endsAt: endsAt.toISOString(),
        teamIds: selectedTeams,
      });
      setName('');
      onClose();
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <AppModal
      isOpen={isOpen}
      title="New validation session"
      subtitle="Marks a time window so exports can be filtered to it. Recording is always on."
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="btn btn--ghost" onClick={onClose} disabled={saving}>Cancel</button>
          <button type="button" className="btn btn--primary" onClick={submit} disabled={saving}>
            {saving ? 'Saving…' : 'Create session'}
          </button>
        </>
      )}
    >
      <div className="vm-form">
        <label className="vm-form__field">
          <span className="tm-field-label">Name</span>
          <input className="tm-input" value={name} placeholder="Validation session — Nov 25, 9–11 AM"
            onChange={(e) => setName(e.target.value)} />
        </label>
        <div className="vm-form__row">
          <label className="vm-form__field">
            <span className="tm-field-label">Date</span>
            <input className="tm-input" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <label className="vm-form__field">
            <span className="tm-field-label">Start</span>
            <input className="tm-input" type="time" value={start} onChange={(e) => setStart(e.target.value)} />
          </label>
          <label className="vm-form__field">
            <span className="tm-field-label">End</span>
            <input className="tm-input" type="time" value={end} onChange={(e) => setEnd(e.target.value)} />
          </label>
        </div>
        <p className="tm-muted">
          Teams: {selectedTeams.length === 0 ? 'all teams' : selectedTeams.join(', ')} (from the team filter).
        </p>
        {error && <p className="vm-error">{error}</p>}
      </div>
    </AppModal>
  );
}

function ValidationMetricsPage() {
  const { toast, showToast, hideToast } = useToast();
  const [selectedTeams, setSelectedTeams] = useState([]);
  const [sessions, setSessions] = useState([]);
  const [period, setPeriod] = useState('all');
  const [sessionId, setSessionId] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState('');
  const [exportMenuOpen, setExportMenuOpen] = useState(false);
  const exportMenuRef = useRef(null);

  useEffect(() => {
    if (!exportMenuOpen) return undefined;
    const onPointerDown = (e) => {
      if (!exportMenuRef.current?.contains(e.target)) setExportMenuOpen(false);
    };
    const onKeyDown = (e) => {
      if (e.key === 'Escape') setExportMenuOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [exportMenuOpen]);
  const [showNewSession, setShowNewSession] = useState(false);

  const filter = useMemo(() => ({
    teamCodes: selectedTeams,
    sessionId: period === 'session' && sessionId ? sessionId : null,
    from: period === 'range' ? toIsoStart(fromDate) : null,
    to: period === 'range' ? toIsoEndExclusive(toDate) : null,
  }), [selectedTeams, period, sessionId, fromDate, toDate]);

  const loadSummary = useCallback(async () => {
    setLoading(true);
    try {
      setSummary(await getValidationSummary(filter));
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setLoading(false);
    }
  }, [filter, showToast]);

  useEffect(() => {
    getValidationSessions().then(setSessions).catch((err) => showToast(err.message, 'error'));
  }, [showToast]);

  useEffect(() => {
    if (period === 'session' && !sessionId) return;
    loadSummary();
  }, [loadSummary, period, sessionId]);

  async function handleExport(format) {
    setExporting(format);
    try {
      const { filename } = await exportValidationMetrics(format, filter);
      showToast(`Downloaded ${filename}`, 'success');
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setExporting('');
    }
  }

  async function handleCreateSession(session) {
    const created = await createValidationSession(session);
    setSessions((current) => [created, ...current]);
    setPeriod('session');
    setSessionId(String(created.id));
    showToast('Validation session saved.', 'success');
  }

  async function handleDeleteSession() {
    if (!sessionId) return;
    try {
      await deleteValidationSession(sessionId);
      setSessions((current) => current.filter((s) => String(s.id) !== String(sessionId)));
      setSessionId('');
      showToast('Validation session removed. Recorded data is unchanged.', 'success');
    } catch (err) {
      showToast(err.message, 'error');
    }
  }

  const teamSummaries = useMemo(() => summary?.teams || [], [summary]);
  const teamsMet = useMemo(() => teamsMetByMetric(teamSummaries), [teamSummaries]);
  const showOverview = selectedTeams.length === 0 && summary?.overall && teamSummaries.length > 0;
  const exportLabel = selectedTeams.length === 1 ? `team ${selectedTeams[0]}` : (selectedTeams.length ? `${selectedTeams.length} teams` : 'all teams');

  return (
    <div className="vm-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Metrics"
        subtitle="Recorded automatically for every team."
        actions={(
          <div className="vm-actions">
            <button type="button" className="btn btn--ghost" onClick={loadSummary} disabled={loading}>
              <RefreshCw size={14} /> Refresh
            </button>
            <div className="vm-export" ref={exportMenuRef}>
              <button type="button" className="btn btn--primary" disabled={!!exporting}
                aria-haspopup="menu" aria-expanded={exportMenuOpen}
                onClick={() => setExportMenuOpen((open) => !open)}>
                {exporting ? 'Exporting…' : 'Export'} <ChevronDown size={14} />
              </button>
              {exportMenuOpen && (
                <div className="vm-export__menu" role="menu">
                  {EXPORT_FORMATS.map((f) => (
                    <button key={f.key} type="button" role="menuitem" className="vm-export__item"
                      onClick={() => { setExportMenuOpen(false); handleExport(f.key); }}>
                      {f.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      />

      <section className="card vm-filters">
        <div className="vm-filters__group">
          <span className="tm-field-label">Period</span>
          <div className="teacher-chip-group">
            {PERIODS.map((p) => (
              <button key={p.key} type="button"
                className={`teacher-chip ${period === p.key ? 'teacher-chip--active' : ''}`}
                onClick={() => setPeriod(p.key)}>
                {p.label}
              </button>
            ))}
          </div>
          {period === 'session' && (
            <div className="vm-filters__inline">
              <select className="tm-select vm-session-select" value={sessionId}
                onChange={(e) => setSessionId(e.target.value)} aria-label="Validation session">
                <option value="">{sessions.length ? 'Choose a session…' : 'No sessions yet'}</option>
                {sessions.map((s) => <option key={s.id} value={s.id}>{sessionLabel(s)}</option>)}
              </select>
              <button type="button" className="btn btn--ghost" onClick={() => setShowNewSession(true)}>
                <Plus size={14} /> New session
              </button>
              {sessionId && (
                <button type="button" className="btn btn--ghost" onClick={handleDeleteSession} aria-label="Delete session">
                  <Trash2 size={14} />
                </button>
              )}
            </div>
          )}
          {period === 'range' && (
            <div className="vm-filters__inline">
              <input className="tm-input vm-date" type="date" value={fromDate} aria-label="From"
                onChange={(e) => setFromDate(e.target.value)} />
              <span className="tm-muted">to</span>
              <input className="tm-input vm-date" type="date" value={toDate} aria-label="To"
                onChange={(e) => setToDate(e.target.value)} />
            </div>
          )}
        </div>

        <div className="vm-filters__group">
          <span className="tm-field-label">Teams</span>
          <TeamSelect
            value={selectedTeams[0] || ''}
            onChange={(code) => setSelectedTeams(code ? [code] : [])}
            allLabel="All teams"
          />
        </div>
        <p className="tm-muted vm-export-note">Exports include {exportLabel} for the selected period.</p>
      </section>

      {loading ? (
        <p className="tm-muted">Loading metrics…</p>
      ) : period === 'session' && !sessionId ? (
        <div className="empty-state">Choose a validation session, or create one for the time window you ran.</div>
      ) : teamSummaries.length === 0 ? (
        <div className="empty-state">
          No validation data recorded for this period yet. Metrics appear as teams extract goals, map stages,
          run analysis and export reports.
        </div>
      ) : (
        <div className="vm-team-list">
          {showOverview
            ? <TeamCard team={summary.overall} teamsMet={teamsMet} teamCount={teamSummaries.length} />
            : teamSummaries.map((team) => <TeamCard key={team.teamId} team={team} />)}
        </div>
      )}

      <NewSessionModal
        isOpen={showNewSession}
        onClose={() => setShowNewSession(false)}
        onCreate={handleCreateSession}
        selectedTeams={selectedTeams}
      />
    </div>
  );
}

export default ValidationMetricsPage;
