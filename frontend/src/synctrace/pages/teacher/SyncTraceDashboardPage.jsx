import { useCallback, useEffect, useState } from 'react';
import SyncTraceSidebar from '../../components/teacher/SyncTraceSidebar';
import OverviewPage from './OverviewPage';
import TraceabilityMappingPage from './TraceabilityMappingPage';
import GroupTraceabilityPage from './GroupTraceabilityPage';
import TraceabilityResultsPage from './TraceabilityResultsPage';
import SourceCodePage from './SourceCodePage';
import { useSyncTraceProgress } from '../../hooks/useSyncTraceProgress';
import {
  buildSyncTracePath,
  isSyncTracePath,
  navigatePath,
  pathToView,
} from '../../routes';
import '../../../styles/pages/teacher-dashboard.css';
import '../../../styles/components/layout.css';

function readLocation() {
  const loc = pathToView(window.location.pathname, window.location.search);
  return loc || {
    view: 'overview',
    teamCode: null,
    focusGoalId: null,
    focusStep: null,
    focusDocType: null,
  };
}

function SyncTraceDashboardPage({ onBack }) {
  const [loc, setLoc] = useState(readLocation);
  const progress = useSyncTraceProgress();

  const syncFromUrl = useCallback(() => {
    if (!isSyncTracePath()) return;
    const next = readLocation();
    if (next.redirectTo) {
      window.history.replaceState(null, '', next.redirectTo);
      setLoc(pathToView(next.redirectTo, window.location.search) || {
        view: 'overview',
        teamCode: null,
        focusGoalId: null,
        focusStep: null,
        focusDocType: null,
      });
      return;
    }
    setLoc(next);
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/exhaustive-deps
    syncFromUrl();
    window.addEventListener('popstate', syncFromUrl);
    return () => window.removeEventListener('popstate', syncFromUrl);
  }, [syncFromUrl]);

  function handleNavigate(view, options = {}) {
    const path = buildSyncTracePath(view, {
      teamCode: options.teamCode,
      focusGoalId: options.focusGoalId,
      focusStep: options.focusStep,
      focusDocType: options.focusDocType,
    });
    navigatePath(path);
  }

  function openGroupResults(teamCode) {
    handleNavigate('results', { teamCode });
  }

  function handleBack() {
    onBack?.();
  }

  const currentView = loc.view;
  const focusStep = loc.focusStep;
  const selectedTeamCode = loc.teamCode;

  // Sidebar: highlight "Overview" when viewing a group detail
  const sidebarView = currentView === 'results' ? 'overview' : currentView;

  return (
    <div className="layout layout--teacher">
      <SyncTraceSidebar currentView={sidebarView} onNavigate={handleNavigate} onBack={handleBack} />

      <main className="layout__main">
        {currentView === 'overview' && (
          <OverviewPage onOpenGroup={openGroupResults} />
        )}
        {currentView === 'traceability' && (
          <TraceabilityMappingPage focusStep={focusStep} />
        )}
        {currentView === 'traceability-results' && (
          <TraceabilityResultsPage onNavigate={handleNavigate} />
        )}
        {currentView === 'source' && (
          <SourceCodePage onProgressRefresh={progress.refresh} />
        )}
        {currentView === 'results' && (
          <GroupTraceabilityPage
            teamCode={selectedTeamCode}
            onBack={() => handleNavigate('overview')}
            onNavigate={handleNavigate}
          />
        )}
      </main>
    </div>
  );
}

export default SyncTraceDashboardPage;
