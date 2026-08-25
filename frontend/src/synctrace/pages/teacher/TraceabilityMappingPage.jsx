import { useEffect, useState } from 'react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import TeamSelect from '../../components/common/TeamSelect';
import MappingStageSelector from '../../components/teacher/mapping/MappingStageSelector';
import MappingStatusBadge from '../../components/teacher/mapping/MappingStatusBadge';
import MappingWorkspace from '../../components/teacher/mapping/MappingWorkspace';
import ExtractSmartGoalsDialog from '../../components/teacher/mapping/ExtractSmartGoalsDialog';
import ExtractComponentsDialog from '../../components/teacher/mapping/ExtractComponentsDialog';
import AddComponentDialog from '../../components/teacher/mapping/AddComponentDialog';
import ComponentDetailModal from '../../components/teacher/ComponentDetailModal';
import { useSelectedTeam } from '../../hooks/useSelectedTeam';
import { useStagedTraceability } from '../../hooks/useStagedTraceability';
import './TraceabilityMappingPage.css';

function TraceabilityMappingPage({ focusStep }) {
  const { toast, showToast, hideToast } = useToast();
  const [selectedTeam, setSelectedTeam] = useSelectedTeam();
  const tm = useStagedTraceability(showToast, selectedTeam);

  const [isExtractGoalsOpen, setIsExtractGoalsOpen] = useState(false);
  const [isExtractComponentsOpen, setIsExtractComponentsOpen] = useState(false);
  const [addComponentDocType, setAddComponentDocType] = useState(null);
  const [previewComponent, setPreviewComponent] = useState(null);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (focusStep === 'goals') setIsExtractGoalsOpen(true);
    if (focusStep === 'library') setIsExtractComponentsOpen(true);
  }, [focusStep]);

  const isProposalStage = tm.stage.sourceType === 'PROPOSAL';
  const isSaving = tm.saveState === 'saving';
  const isVerifying = tm.saveState === 'verifying';
  const isBusy = isSaving || isVerifying;

  function saveButtonLabel() {
    if (isSaving) return 'Saving mapping…';
    if (isVerifying) return 'Verifying traceability…';
    return 'Save Mapping';
  }

  const missingArtifact = tm.sourceItems.length === 0 || tm.targetItems.length === 0;
  const missingWarning = (() => {
    if (!missingArtifact) return null;
    if (isProposalStage && tm.sourceItems.length === 0) {
      return 'No SMART Goals are available yet. Extract them from the evaluated proposal before establishing Proposal → SRS mappings.';
    }
    if (tm.sourceItems.length === 0 && tm.precedingStage) {
      const libraryHasComponents = (tm.components[tm.stage.sourceType] || []).length > 0;
      if (libraryHasComponents) {
        return `No ${tm.stage.sourceType} components have been mapped yet in ${tm.precedingStage.label}. Establish those mappings before starting ${tm.stage.label}.`;
      }
    }
    const missingSide = tm.sourceItems.length === 0 ? tm.stage.sourceType : tm.stage.targetType;
    return `No ${missingSide} components are currently available. Extract or add components before establishing ${tm.stage.label} mappings.`;
  })();

  return (
    <div className="stm-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Traceability Mapping"
        subtitle="Establish traceability relationships between project artifacts, one staged mapping at a time."
        actions={
          <div className="teacher-header-actions">
            {isProposalStage && (
              <button className="btn btn--soft" onClick={() => setIsExtractGoalsOpen(true)}>
                Extract SMART Goals
              </button>
            )}
            <button className="btn btn--soft" onClick={() => setIsExtractComponentsOpen(true)}>
              Extract Components
            </button>
            <button className="btn btn--primary" disabled={isBusy} onClick={tm.saveMapping}>
              {saveButtonLabel()}
            </button>
          </div>
        }
      />

      <div className="stm-context-row">
        <TeamSelect value={selectedTeam} onChange={setSelectedTeam} />
        <MappingStageSelector stages={tm.stages} selectedStage={tm.selectedStage} onChange={tm.setSelectedStage} />
        <MappingStatusBadge status={tm.stageStatus} />
      </div>

      {missingWarning && (
        <div className="stm-warning-banner">
          <span>{missingWarning}</span>
        </div>
      )}

      <MappingWorkspace
        tm={tm}
        onExtractGoalsClick={() => setIsExtractGoalsOpen(true)}
        onAddComponentClick={(docType) => setAddComponentDocType(docType)}
        onPreviewComponentClick={setPreviewComponent}
        onRemoveComponentClick={(docType, id) => tm.removeComponent(docType, id)}
      />

      {tm.verification.status !== 'idle' && (
        <div className={`stm-verification-banner stm-verification-banner--${tm.verification.status}`}>
          <span>{tm.verification.message}</span>
        </div>
      )}

      <ExtractSmartGoalsDialog
        isOpen={isExtractGoalsOpen}
        onClose={() => setIsExtractGoalsOpen(false)}
        teamCode={selectedTeam}
        extracting={tm.extractingGoals}
        onExtract={tm.extractSmartGoals}
      />

      <ExtractComponentsDialog
        isOpen={isExtractComponentsOpen}
        onClose={() => setIsExtractComponentsOpen(false)}
        teamCode={selectedTeam}
        extracting={tm.extractingComponents}
        onExtract={tm.extractComponents}
      />

      <AddComponentDialog
        isOpen={Boolean(addComponentDocType)}
        onClose={() => setAddComponentDocType(null)}
        docType={addComponentDocType}
        onAdd={(data) => tm.addComponent(addComponentDocType, data)}
      />

      <ComponentDetailModal
        component={previewComponent}
        onClose={() => setPreviewComponent(null)}
        showToast={showToast}
        onRenamed={(updated) => {
          setPreviewComponent(updated);
          tm.refreshComponents();
        }}
      />
    </div>
  );
}

export default TraceabilityMappingPage;
