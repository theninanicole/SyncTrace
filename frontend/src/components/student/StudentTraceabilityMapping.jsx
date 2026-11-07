import { useState } from 'react';
import AddComponentDialog from '../../synctrace/components/teacher/mapping/AddComponentDialog';
import ComponentDetailModal from '../../synctrace/components/teacher/ComponentDetailModal';
import ExtractComponentsDialog from '../../synctrace/components/teacher/mapping/ExtractComponentsDialog';
import ExtractSmartGoalsDialog from '../../synctrace/components/teacher/mapping/ExtractSmartGoalsDialog';
import MappingStageSelector from '../../synctrace/components/teacher/mapping/MappingStageSelector';
import MappingStatusBadge from '../../synctrace/components/teacher/mapping/MappingStatusBadge';
import MappingWorkspace from '../../synctrace/components/teacher/mapping/MappingWorkspace';
import { useStagedTraceability } from '../../synctrace/hooks/useStagedTraceability';
import { useMappingSuggestions } from '../../synctrace/hooks/useMappingSuggestions';
import SuggestionReviewPanel from '../../synctrace/components/teacher/mapping/SuggestionReviewPanel';
import { formatDateTime } from '../../utils/dashboardUtils';
import { mappingStageLabel } from '../../synctrace/constants';
import '../../synctrace/pages/teacher/TraceabilityMappingPage.css';

function StudentTraceabilityMapping({ teamCode, showToast, hasEvaluatedDocument = false }) {
  const tm = useStagedTraceability(showToast, teamCode);
  const [suggestionsOpen, setSuggestionsOpen] = useState(false);
  const suggestionState = useMappingSuggestions(teamCode, tm.selectedStage, async () => {});
  const [previewComponent, setPreviewComponent] = useState(null);
  const [isExtractGoalsOpen, setIsExtractGoalsOpen] = useState(false);
  const [isExtractComponentsOpen, setIsExtractComponentsOpen] = useState(false);
  const [addComponentDocType, setAddComponentDocType] = useState(null);
  const isBusy = tm.saveState === 'saving' || tm.saveState === 'verifying';
  const isProposalStage = tm.stage.sourceType === 'PROPOSAL';

  return (
    <section className="card student-traceability-card">
      <div className="student-section-heading student-section-heading--mapping">
        <div className="stm-context-row student-stm-context-row">
          <MappingStageSelector stages={tm.stages} selectedStage={tm.selectedStage} onChange={tm.setSelectedStage} />
          <MappingStatusBadge status={tm.stageStatus} />
        </div>
        <div className="student-header-actions">
          {hasEvaluatedDocument && (
            <>
              {isProposalStage && (
                <button className="btn btn--soft student-mapping__extract-goals" type="button" onClick={() => setIsExtractGoalsOpen(true)}>
                  Extract SMART Goals
                </button>
              )}
              <button className="btn btn--soft student-mapping__extract-components" type="button" onClick={() => setIsExtractComponentsOpen(true)}>
                Extract Components
              </button>
            </>
          )}
          <button className="btn btn--primary student-mapping__save" type="button" onClick={tm.saveMapping} disabled={isBusy || !teamCode}>
            {tm.saveState === 'saving' ? 'Saving mapping...' : tm.saveState === 'verifying' ? 'Verifying...' : 'Save Mapping'}
          </button>
          <button className="btn btn--soft" type="button" disabled={isBusy || !teamCode || tm.sourceItems.length === 0 || tm.targetItems.length === 0} onClick={() => { setSuggestionsOpen(true); suggestionState.generate().catch(() => {}); }}>
            Suggest Mappings
          </button>
        </div>
      </div>

      {tm.mappingActivity && (
        <p className="tm-muted student-mapping-activity">
          Last mapping: {formatDateTime(tm.mappingActivity.performedAt)} by {tm.mappingActivity.performedBy}
          {tm.mappingActivity.stage ? ` (${mappingStageLabel(tm.mappingActivity.stage)})` : ''}
        </p>
      )}

      {!teamCode ? (
        <div className="student-traceability-locked">
          <h3 className="student-empty__title">No team connected</h3>
                  </div>
      ) : (
        <MappingWorkspace
          tm={tm}
          readOnly
          onAddComponentClick={setAddComponentDocType}
          onExtractComponentsClick={hasEvaluatedDocument ? () => setIsExtractComponentsOpen(true) : undefined}
          onPreviewComponentClick={setPreviewComponent}
          onRemoveComponentClick={(docType, id) => tm.removeComponent(docType, id)}
        />
      )}
      {suggestionsOpen && (
        <SuggestionReviewPanel
          suggestions={suggestionState.suggestions}
          sourceItems={tm.sourceItems}
          targetItems={tm.targetItems}
          onDecide={suggestionState.decide}
          onAcceptAll={suggestionState.acceptAll}
          onClose={() => { suggestionState.discard(); setSuggestionsOpen(false); }}
          state={suggestionState.state}
          progress={suggestionState.progress}
          error={suggestionState.error}
        />
      )}

      {tm.verification.status !== 'idle' && (
        <div className={`stm-verification-banner stm-verification-banner--${tm.verification.status}`}>
          {tm.verification.message}
        </div>
      )}

      <AddComponentDialog
        isOpen={Boolean(addComponentDocType)}
        onClose={() => setAddComponentDocType(null)}
        docType={addComponentDocType}
        onAdd={(data) => tm.addComponent(addComponentDocType, data)}
      />

      <ComponentDetailModal
        component={previewComponent}
        stage={tm.stage}
        onClose={() => setPreviewComponent(null)}
        showToast={showToast}
        onRenamed={(updated) => {
          setPreviewComponent(updated);
          tm.handleComponentUpdated(updated);
        }}
        extractedTypesOnly
      />

      <ExtractSmartGoalsDialog
        isOpen={isExtractGoalsOpen}
        onClose={() => setIsExtractGoalsOpen(false)}
        teamCode={teamCode}
        extracting={tm.extractingGoals}
        onExtract={tm.extractSmartGoals}
        onSave={tm.saveReviewedGoals}
      />

      <ExtractComponentsDialog
        isOpen={isExtractComponentsOpen}
        onClose={() => setIsExtractComponentsOpen(false)}
        teamCode={teamCode}
        stage={tm.stage}
        extracting={tm.extractingComponents}
        progress={tm.extractionProgress}
        onExtract={tm.extractComponents}
      />
    </section>
  );
}

export default StudentTraceabilityMapping;