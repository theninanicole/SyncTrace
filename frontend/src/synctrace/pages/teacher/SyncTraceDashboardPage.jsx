import { useState } from 'react';
import SyncTraceSidebar from '../../components/teacher/SyncTraceSidebar';
import OverviewPage from './OverviewPage';
import TraceabilityMappingPage from './TraceabilityMappingPage';
import '../../../styles/pages/teacher-dashboard.css';
import '../../../styles/components/layout.css';

function SyncTraceDashboardPage({ onBack }) {
  const [currentView, setCurrentView] = useState('overview');
  const [focusGoalId, setFocusGoalId] = useState(null);

  function openGoal(goalId) {
    setFocusGoalId(goalId);
    setCurrentView('traceability');
  }

  return (
    <div className="layout layout--teacher">
      <SyncTraceSidebar currentView={currentView} onNavigate={setCurrentView} onBack={onBack} />

      <main className="layout__main">
        {currentView === 'overview' && <OverviewPage onOpenGoal={openGoal} />}
        {currentView === 'traceability' && <TraceabilityMappingPage initialGoalId={focusGoalId} />}
      </main>
    </div>
  );
}

export default SyncTraceDashboardPage;
