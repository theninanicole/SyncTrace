import { useMemo, useState } from 'react';
import { ChevronRight, CircleAlert, CircleCheck, ListChecks, Search, TriangleAlert } from 'lucide-react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { formatDate } from '../../../utils/dashboardUtils';
import { useGroupOverview, STATUS_META } from '../../hooks/useGroupOverview';
import './TraceabilityMappingPage.css';
import './OverviewPage.css';

const FILTERS = [
  { key: 'all', label: 'All Groups' },
  { key: 'ready', label: 'Ready' },
  { key: 'revision', label: 'Revision' },
  { key: 'critical', label: 'Critical' },
];

function OverviewPage({ onOpenGroup }) {
  const { toast, showToast, hideToast } = useToast();
  const { groups, loading } = useGroupOverview(showToast);
  const [search, setSearch] = useState('');
  const [activeFilter, setActiveFilter] = useState('all');

  const stats = useMemo(() => ({
    total: groups.length,
    ready: groups.filter((g) => g.status === 'ready').length,
    revision: groups.filter((g) => g.status === 'revision').length,
    critical: groups.filter((g) => g.status === 'critical').length,
  }), [groups]);

  const filteredGroups = useMemo(() => groups.filter((g) => {
    if (activeFilter !== 'all' && g.status !== activeFilter) return false;
    const query = search.trim().toLowerCase();
    if (query && !g.teamCode.toLowerCase().includes(query) && !g.section.toLowerCase().includes(query)) return false;
    return true;
  }), [groups, activeFilter, search]);

  return (
    <div className="ov-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Overview"
        subtitle="Monitor traceability and project readiness across all groups"
      />

      <div className="ov-stats">
        <div className="ov-stat-card">
          <span className="ov-stat-card__label"><ListChecks size={14} /> Total Groups</span>
          <span className="ov-stat-card__value">{stats.total}</span>
        </div>
        <div className="ov-stat-card ov-stat-card--ok">
          <span className="ov-stat-card__label"><CircleCheck size={14} /> Ready</span>
          <span className="ov-stat-card__value">{stats.ready}</span>
        </div>
        <div className="ov-stat-card ov-stat-card--warn">
          <span className="ov-stat-card__label"><TriangleAlert size={14} /> Needs Revision</span>
          <span className="ov-stat-card__value">{stats.revision}</span>
        </div>
        <div className="ov-stat-card ov-stat-card--critical">
          <span className="ov-stat-card__label"><CircleAlert size={14} /> Critical Gaps</span>
          <span className="ov-stat-card__value">{stats.critical}</span>
        </div>
      </div>

      <div className="ov-toolbar">
        <div className="ov-search">
          <Search size={16} className="ov-search__icon" />
          <input
            type="search"
            placeholder="Search by team code or section..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Search groups"
          />
        </div>
        <div className="teacher-chip-group">
          {FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              className={`teacher-chip ${activeFilter === f.key ? 'teacher-chip--active' : ''}`}
              onClick={() => setActiveFilter(f.key)}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <p className="ov-count">
        {loading ? 'Loading...' : `${filteredGroups.length} group${filteredGroups.length === 1 ? '' : 's'} found`}
      </p>

      {loading ? (
        <p className="tm-muted">Loading groups...</p>
      ) : filteredGroups.length === 0 ? (
        <div className="empty-state ov-empty-help">
          <p className="ov-empty-help__title">
            {groups.length === 0 ? 'No group cards yet — that is normal at the start.' : 'No groups match your search or filter.'}
          </p>
          {groups.length === 0 && (
            <p>
              Groups show up after you extract proposal goals, map document/code components, and teams have recognizable codes.
            </p>
          )}
        </div>
      ) : (
        <div className="ov-list">
          {filteredGroups.map((g) => {
            const meta = STATUS_META[g.status];
            return (
              <button type="button" className="ov-goal-card" key={g.teamCode} onClick={() => onOpenGroup?.(g.teamCode)}>
                <div className={`ov-ring ov-ring--${g.status}`} style={{ '--pct': g.percent }}>
                  <span className="ov-ring__value">{g.percent}%</span>
                </div>

                <div className="ov-goal-card__body">
                  <div className="ov-goal-card__title-row">
                    <h3 className="ov-goal-card__title">Team {g.teamCode}</h3>
                    {g.section && <span className="tm-badge">{g.section}</span>}
                  </div>
                  <div className="ov-goal-card__meta">
                    <span className={`status-chip ${meta.chip}`}>{meta.label}</span>
                    <span className="ov-goal-card__date">Readiness {g.readinessScore ?? g.percent}%</span>
                    {typeof g.findingCount === 'number' && (
                      <span className="ov-goal-card__date">{g.findingCount} finding{g.findingCount === 1 ? '' : 's'}</span>
                    )}
                    {g.lastTraceability && (
                      <span className="ov-goal-card__date">Traceability updated at {formatDate(g.lastTraceability)}</span>
                    )}
                  </div>
                  <p className="ov-goal-card__next">Click to open this team’s matrix and gaps</p>
                </div>

                <ChevronRight size={18} className="ov-goal-card__chevron" />
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default OverviewPage;
