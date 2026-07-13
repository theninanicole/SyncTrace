import { useState } from 'react';
import SyncTraceSidebar from '../../components/teacher/SyncTraceSidebar';
import OverviewPage from './OverviewPage';
import TraceabilityMappingPage from './TraceabilityMappingPage';
import GroupTraceabilityPage from './GroupTraceabilityPage';
import '../../../styles/pages/teacher-dashboard.css';
import '../../../styles/components/layout.css';

function SyncTraceDashboardPage({ onBack }) {
  const [currentView, setCurrentView] = useState('overview');
  const [focusGoalId, setFocusGoalId] = useState(null);
  const [selectedTeamCode, setSelectedTeamCode] = useState(null);

  function openGoal(goalId) {
    setFocusGoalId(goalId);
    setCurrentView('traceability');
  }

  function openGroupResults(teamCode) {
    setSelectedTeamCode(teamCode);
    setCurrentView('results');
  }

  return (
    <div className="layout layout--teacher">
      <SyncTraceSidebar currentView={currentView} onNavigate={setCurrentView} onBack={onBack} />

      <main className="layout__main">
        {currentView === 'overview' && <OverviewPage onOpenGoal={openGoal} onOpenGroup={openGroupResults} />}
        {currentView === 'traceability' && <TraceabilityMappingPage initialGoalId={focusGoalId} />}
        {currentView === 'results' && (
          <GroupTraceabilityPage teamCode={selectedTeamCode} onBack={() => setCurrentView('overview')} />
        )}
      </main>
    </div>
  );
}

export default SyncTraceDashboardPage;
