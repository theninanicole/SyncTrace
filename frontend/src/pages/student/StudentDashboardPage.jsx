import { useState } from 'react';
import PanelHeader from '../../components/common/PanelHeader';
import StudentReportModal from '../../components/student/StudentReportModal';
import StudentReportsTable from '../../components/student/StudentReportsTable';
import StudentSidebar from '../../components/student/StudentSidebar';
import StudentTraceabilityResults from '../../components/student/StudentTraceabilityResults';
import StudentTraceabilityMapping from '../../components/student/StudentTraceabilityMapping';
import SourceCodePage from '../../synctrace/pages/teacher/SourceCodePage';
import ToastMessage from '../../components/common/ToastMessage';
import TutorialOverlay from '../../components/common/TutorialOverlay';
import { useStudentReports } from '../../hooks/useStudentReports';
import { useTutorial } from '../../hooks/useTutorial';
import { studentTutorialSteps } from '../../tutorials/studentTutorial';
import { TUTORIAL_TYPES } from '../../tutorials/tutorialConfig';
import '../../styles/pages/student-dashboard.css';
import '../../styles/components/layout.css';
import '../../styles/components/tutorial.css';
import { getStudentReportById } from '../../api';
import { useToast } from '../../hooks/useToast';

const TRACEABILITY_TABS = [
  { key: 'mapping', label: 'Mapping', subtitle: 'Map your team\'s artifacts to the SMART goals they support.' },
  { key: 'results', label: 'Results', subtitle: 'See how your goals trace across documents and implementation.' },
  { key: 'source', label: 'Source Code', subtitle: 'Pull your GitHub files into Implementation components and check them against your SDD.' },
];

function StudentDashboardPage({ studentData }) {
  const vm = useStudentReports(studentData.groupCode);
  const { toast, showToast, hideToast } = useToast();
  const tutorial = useTutorial({
    tutorialType: TUTORIAL_TYPES.STUDENT,
    userKey: studentData?.email || studentData?.googleEmail || studentData?.studentName,
    maxRuns: 2,
    autoStart: true,
  });

  const [selectedReport, setSelectedReport]       = useState(null);
  const [isFetchingDetails, setIsFetchingDetails] = useState(false);
  const [currentView, setCurrentView]             = useState('evaluations');
  const [hasOpenedTraceability, setHasOpenedTraceability] = useState(false);
  const [traceTab, setTraceTab]                   = useState('mapping');
  // Tabs stay mounted once opened so unsaved mapping edits and ingestion state survive switching.
  const [visitedTraceTabs, setVisitedTraceTabs]   = useState(() => new Set(['mapping']));

  function openTraceTab(key) {
    setTraceTab(key);
    setVisitedTraceTabs((prev) => (prev.has(key) ? prev : new Set(prev).add(key)));
  }

  function navigate(view) {
    setCurrentView(view);
    if (view === 'traceability') setHasOpenedTraceability(true);
  }

  const activeTraceTab = TRACEABILITY_TABS.find((t) => t.key === traceTab);

  async function handleOpenReport(reportSummary) {
    vm.markViewed(reportSummary.id);
    setSelectedReport(null);
    setIsFetchingDetails(true);
    try {
      const fullReport = await getStudentReportById(reportSummary.id);
      setSelectedReport(fullReport);
    } catch (error) {
      console.error('Failed to load report images', error);
      setSelectedReport(reportSummary);
    } finally {
      setIsFetchingDetails(false);
    }
  }

  function handleClose() {
    setSelectedReport(null);
    setIsFetchingDetails(false);
  }

  return (
    <div className="layout layout--student">
      <ToastMessage toast={toast} onClose={hideToast} />
      <StudentSidebar
        studentData={studentData}
        teamMembers={vm.teamMembers}
        onTutorialStart={tutorial.startTutorial}
        currentView={currentView}
        onNavigate={navigate}
      />

      <TutorialOverlay
        steps={studentTutorialSteps}
        run={tutorial.isTutorialOpen}
        stepIndex={tutorial.currentStepIndex}
        onNext={() => tutorial.nextStep(studentTutorialSteps.length)}
        onPrev={tutorial.prevStep}
        onClose={tutorial.closeTutorial}
      />

      <main className="layout__main" hidden={currentView !== 'evaluations'}>
        <PanelHeader
          title="My Team Evaluations"
          subtitle="View feedback sent by your professor."
          actions={
            <div className="student-header-actions">
              <input
                type="search"
                className="student-header-search"
                placeholder="Search evaluations"
                value={vm.searchQuery}
                onChange={(e) => vm.setSearchQuery(e.target.value)}
                aria-label="Search evaluations"
              />
              <button className="btn btn--primary" onClick={vm.refresh} disabled={vm.loading}>
                {vm.loading ? 'Checking...' : 'Check for Updates'}
              </button>
            </div>
          }
        />

        <section className="student-stats" aria-label="Evaluation summary">
          <div className="student-stats__total">
            <span className="student-stats__total-count">{vm.allReportCount}</span>
            <span className="student-stats__total-label">Total Evaluations</span>
          </div>
          <div className="student-stats__divider" />
          <div className="student-stats__docs">
            {vm.docStats.map((doc) => (
              <div key={doc.type} className="student-stats__doc">
                <span className="student-stats__doc-type">{doc.type}</span>
                <span className="student-stats__doc-count">{doc.count}</span>
              </div>
            ))}
          </div>
        </section>

        <div className="card student-reports-card">
          <nav className="student-doc-tabs" aria-label="Filter by document type">
            <button
              type="button"
              className={`student-doc-tab student-doc-tab--all${vm.selectedDocType === '' ? ' student-doc-tab--active' : ''}`}
              onClick={() => vm.setSelectedDocType('')}
            >
              All
            </button>
            {vm.docTypes.map((dt) => (
              <button
                key={dt}
                type="button"
                className={`student-doc-tab student-doc-tab--${dt.toLowerCase().replace(/\s+/g, '-')}${vm.selectedDocType === dt ? ' student-doc-tab--active' : ''}`}
                onClick={() => vm.setSelectedDocType(dt)}
              >
                {dt}
              </button>
            ))}
          </nav>

          <StudentReportsTable
            reports={vm.reports}
            loading={vm.loading}
            viewedIds={vm.viewedIds}
            onOpen={handleOpenReport}
          />
        </div>
      </main>

      {hasOpenedTraceability && (
        <main className="layout__main" hidden={currentView !== 'traceability'}>
          <PanelHeader title="Traceability" subtitle={activeTraceTab.subtitle} />

          <nav className="student-trace-tabs" aria-label="Traceability sections">
            {TRACEABILITY_TABS.map((tab) => (
              <button
                key={tab.key}
                type="button"
                className={`student-doc-tab student-trace-tab--${tab.key}${traceTab === tab.key ? ' student-doc-tab--active' : ''}`}
                onClick={() => openTraceTab(tab.key)}
                aria-pressed={traceTab === tab.key}
              >
                {tab.label}
              </button>
            ))}
          </nav>

          {visitedTraceTabs.has('mapping') && (
            <div className="student-trace-panel student-trace-panel--mapping" hidden={traceTab !== 'mapping'}>
              <StudentTraceabilityMapping
                teamCode={studentData.groupCode}
                showToast={showToast}
                hasEvaluatedDocument={vm.allReportCount > 0}
              />
            </div>
          )}
          {visitedTraceTabs.has('results') && (
            <div className="student-trace-panel student-trace-panel--results" hidden={traceTab !== 'results'}>
              <StudentTraceabilityResults teamCode={studentData.groupCode} />
            </div>
          )}
          {visitedTraceTabs.has('source') && (
            <div className="student-trace-panel student-trace-panel--source" hidden={traceTab !== 'source'}>
              {studentData.groupCode ? (
                <SourceCodePage teamCode={studentData.groupCode} />
              ) : (
                <div className="card student-traceability-locked">
                  <h3 className="student-empty__title">No team connected</h3>
                  <p className="student-empty__text">Source code ingestion is available once your team is connected.</p>
                </div>
              )}
            </div>
          )}
        </main>
      )}

      <StudentReportModal
        report={selectedReport}
        isLoading={isFetchingDetails}
        onClose={handleClose}
      />
    </div>
  );
}

export default StudentDashboardPage;
