import { useState } from 'react';
import ComponentDetailModal from '../../synctrace/components/teacher/ComponentDetailModal';
import ExtractComponentsDialog from '../../synctrace/components/teacher/mapping/ExtractComponentsDialog';
import ExtractSmartGoalsDialog from '../../synctrace/components/teacher/mapping/ExtractSmartGoalsDialog';
import MappingStageSelector from '../../synctrace/components/teacher/mapping/MappingStageSelector';
import MappingStatusBadge from '../../synctrace/components/teacher/mapping/MappingStatusBadge';
import MappingWorkspace from '../../synctrace/components/teacher/mapping/MappingWorkspace';
import { useStagedTraceability } from '../../synctrace/hooks/useStagedTraceability';
import { formatDateTime } from '../../utils/dashboardUtils';
import { mappingStageLabel } from '../../synctrace/constants';
import '../../synctrace/pages/teacher/TraceabilityMappingPage.css';

function StudentTraceabilityMapping({ teamCode, showToast, hasEvaluatedDocument = false }) {
  const tm = useStagedTraceability(showToast, teamCode);
  const [previewComponent, setPreviewComponent] = useState(null);
  const [isExtractGoalsOpen, setIsExtractGoalsOpen] = useState(false);
  const [isExtractComponentsOpen, setIsExtractComponentsOpen] = useState(false);
  const isBusy = tm.saveState === 'saving' || tm.saveState === 'verifying';

  return (
    <section className="card student-traceability-card">
      <div className="student-section-heading">
        <div>
          <h2 className="card__title">Traceability Mapping</h2>
          <p className="student-section-heading__subtitle">
            Map your team&apos;s published artifacts to the SMART goals they support, then save the mapping for review.
          </p>
        </div>
        <div className="student-header-actions">
          {hasEvaluatedDocument && (
            <>
              <button className="btn btn--soft" type="button" onClick={() => setIsExtractGoalsOpen(true)}>
                Extract SMART Goals
              </button>
              <button className="btn btn--soft" type="button" onClick={() => setIsExtractComponentsOpen(true)}>
                Extract Components
              </button>
            </>
          )}
          <button className="btn btn--primary" type="button" onClick={tm.saveMapping} disabled={isBusy || !teamCode}>
            {tm.saveState === 'saving' ? 'Saving mapping...' : tm.saveState === 'verifying' ? 'Verifying...' : 'Save Mapping'}
          </button>
        </div>
      </div>

      <div className="stm-context-row student-stm-context-row">
        <MappingStageSelector stages={tm.stages} selectedStage={tm.selectedStage} onChange={tm.setSelectedStage} />
        <MappingStatusBadge status={tm.stageStatus} />
      </div>

      {tm.mappingActivity && (
        <p className="tm-muted student-mapping-activity">
          Last mapping: {formatDateTime(tm.mappingActivity.performedAt)} by {tm.mappingActivity.performedBy}
          {tm.mappingActivity.stage ? ` (${mappingStageLabel(tm.mappingActivity.stage)})` : ''}
        </p>
      )}

      {!teamCode ? (
        <div className="student-traceability-locked">
          <h3 className="student-empty__title">Connect your team first</h3>
          <p className="student-empty__text">Traceability mapping is unavailable until your team is connected.</p>
        </div>
      ) : (
        <MappingWorkspace
          tm={tm}
          readOnly
          onPreviewComponentClick={setPreviewComponent}
        />
      )}

      {tm.verification.status !== 'idle' && (
        <div className={`stm-verification-banner stm-verification-banner--${tm.verification.status}`}>
          {tm.verification.message}
        </div>
      )}

      <ComponentDetailModal
        component={previewComponent}
        onClose={() => setPreviewComponent(null)}
        readOnly
      />

      <ExtractSmartGoalsDialog
        isOpen={isExtractGoalsOpen}
        onClose={() => setIsExtractGoalsOpen(false)}
        teamCode={teamCode}
        extracting={tm.extractingGoals}
        onExtract={tm.extractSmartGoals}
      />

      <ExtractComponentsDialog
        isOpen={isExtractComponentsOpen}
        onClose={() => setIsExtractComponentsOpen(false)}
        teamCode={teamCode}
        extracting={tm.extractingComponents}
        onExtract={tm.extractComponents}
      />
    </section>
  );
}

export default StudentTraceabilityMapping;