import { ArrowLeft, ClipboardList, FolderGit2, LayoutDashboard, Table2 } from 'lucide-react';
import { signOut } from '../../../services/authService';
import appLogo from '../../../assets/logo.png';

const NAV_ITEMS = [
  { key: 'overview', label: 'Overview', hint: 'Class readiness', Icon: LayoutDashboard },
  { key: 'traceability', label: 'Goals & Mapping', hint: 'Link goals to artifacts', Icon: ClipboardList },
  { key: 'traceability-results', label: 'Results Matrix', hint: 'Coverage gaps', Icon: Table2 },
  { key: 'source', label: 'Source Code', hint: 'GitHub ingest', Icon: FolderGit2 },
];

function SyncTraceSidebar({ currentView, onNavigate, onBack }) {
  return (
    <aside className="teacher-sidebar">
      <div className="teacher-sidebar__brand">
        <img src={appLogo} alt="SyncTrace logo" className="teacher-sidebar__brand-logo" />
        <span>SyncTrace</span>
      </div>
      <p className="teacher-sidebar__caption">Goal → docs → code continuity</p>

      <button className="btn btn--ghost teacher-sidebar__back" onClick={onBack}>
        <ArrowLeft size={14} /> Back to IEEE Docs Evaluator
      </button>

      <nav className="teacher-sidebar__nav">
        {NAV_ITEMS.map((item) => {
          const Icon = item.Icon;
          return (
            <button
              key={item.key}
              className={`nav-btn nav-btn--${item.key} ${currentView === item.key ? 'nav-btn--active' : ''}`}
              onClick={() => onNavigate(item.key)}
            >
              <span className="st-nav-row">
                <Icon size={15} />
                <span className="st-nav-copy">
                  <span className="st-nav-label">{item.label}</span>
                  <span className="st-nav-hint">{item.hint}</span>
                </span>
              </span>
            </button>
          );
        })}
      </nav>

      <div className="teacher-sidebar__spacer" />

      <button className="btn btn--ghost teacher-sidebar__signout" onClick={signOut}>
        Sign Out
      </button>
    </aside>
  );
}

export default SyncTraceSidebar;
