import { ArrowLeft } from 'lucide-react';
import { signOut } from '../../../services/authService';
import appLogo from '../../../assets/logo.png';

const NAV_ITEMS = [
  { key: 'overview',     label: 'Overview' },
  { key: 'traceability', label: 'Traceability Mapping' },
  { key: 'matrix',       label: 'Matrix' },
  { key: 'gap',          label: 'Gap Analysis' },
];

function SyncTraceSidebar({ currentView, onNavigate, onBack }) {
  return (
    <aside className="teacher-sidebar">
      <div className="teacher-sidebar__brand">
        <img src={appLogo} alt="IEEE Docs Evaluator logo" className="teacher-sidebar__brand-logo" />
        <span>SyncTrace</span>
      </div>
      <p className="teacher-sidebar__caption">Teacher Workspace</p>

      <button className="btn btn--ghost teacher-sidebar__back" onClick={onBack}>
        <ArrowLeft size={14} /> Back to IEEE Docs Evaluator
      </button>

      <nav className="teacher-sidebar__nav">
        {NAV_ITEMS.map((item) => (
          <button
            key={item.key}
            className={`nav-btn nav-btn--${item.key} ${currentView === item.key ? 'nav-btn--active' : ''}`}
            onClick={() => onNavigate(item.key)}
          >
            {item.label}
          </button>
        ))}
      </nav>

      <div className="teacher-sidebar__spacer" />

      <button className="btn btn--ghost teacher-sidebar__signout" onClick={signOut}>
        Sign Out
      </button>
    </aside>
  );
}

export default SyncTraceSidebar;
