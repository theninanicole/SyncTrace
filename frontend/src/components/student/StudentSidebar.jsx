import { Moon, Sun } from 'lucide-react';
import { signOut } from '../../services/authService';
import TutorialButton from '../common/TutorialButton';
import { useTheme } from '../../hooks/useTheme';
import appLogo from '../../assets/logo.png';

const NAV_ITEMS = [
  { key: 'evaluations', label: 'Evaluations' },
  { key: 'traceability', label: 'Traceability' },
];

function StudentSidebar({ studentData, teamMembers = [], onTutorialStart, currentView, onNavigate }) {
  const { themeMode, setThemeMode } = useTheme();
  const toggleTheme = () => {
    setThemeMode(themeMode === 'dark' ? 'light' : 'dark');
  };

  const themeIcon = themeMode === 'dark' ? <Sun size={16} /> : <Moon size={16} />;
  return (
    <aside className="student-sidebar">
      <div className="student-sidebar__brand">
        <img src={appLogo} alt="IEEE Docs Evaluator logo" className="student-sidebar__brand-logo" />
        <span>IEEE Docs Evaluator</span>
      </div>
      <p className="student-sidebar__caption">Student Workspace</p>

      <div className="student-profile">
        <h4 className="student-profile__name">{studentData.studentName}</h4>
        <span className="student-profile__meta">{studentData.section}</span>
      </div>

      {onNavigate && (
        <nav className="student-sidebar__nav" aria-label="Student workspace">
          {NAV_ITEMS.map((item) => (
            <button
              key={item.key}
              type="button"
              className={`student-nav-btn student-nav-btn--${item.key}${currentView === item.key ? ' student-nav-btn--active' : ''}`}
              onClick={() => onNavigate(item.key)}
              aria-current={currentView === item.key ? 'page' : undefined}
            >
              {item.label}
            </button>
          ))}
        </nav>
      )}

      <button
        type="button"
        className="btn btn--ghost student-sidebar__theme-toggle"
        onClick={toggleTheme}
        aria-label={themeMode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
        title={themeMode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
      >
        <span className="student-sidebar__theme-icon" aria-hidden="true">
          {themeIcon}
        </span>
        <span className="student-sidebar__theme-label">
          {themeMode === 'dark' ? 'Light' : 'Dark'}
        </span>
      </button>
      
      <div className="student-detail">
        <span className="student-detail__key">Team Code</span>
        <span className="student-detail__value">{studentData.groupCode}</span>
      </div>

      {teamMembers.length > 0 && (
        <div className="student-members">
          <p className="student-members__title">Team Members ({teamMembers.length})</p>
          <ul className="student-members__list">
            {teamMembers.map((m) => (
              <li
                key={m.studentName}
                className={`student-members__item${
                  m.studentName === studentData.studentName ? ' student-members__item--you' : ''
                }`}
              >
                <span className="student-members__dot" />
                <span className="student-members__name">{m.studentName}</span>
                {m.studentName === studentData.studentName && (
                  <span className="student-members__you-tag">You</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="student-sidebar__spacer" />

      {onTutorialStart && (
        <TutorialButton onClick={onTutorialStart} label="Quick Tutorial" />
      )}

      <button className="btn btn--ghost student-sidebar__signout" onClick={signOut}>
        Sign Out
      </button>
    </aside>
  );
}

export default StudentSidebar;
