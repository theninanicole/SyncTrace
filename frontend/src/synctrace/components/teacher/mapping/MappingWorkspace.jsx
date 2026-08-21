import { DOC_TYPE_LABELS } from '../../../constants';
import SmartGoalPanel from './SmartGoalPanel';
import ComponentLibraryColumn from './ComponentLibraryColumn';
import MappingControls from './MappingControls';

function countBy(mappingsForStage, key) {
  const counts = {};
  mappingsForStage.forEach((m) => {
    counts[m[key]] = (counts[m[key]] || 0) + 1;
  });
  return counts;
}

function MappingWorkspace({ tm, onExtractGoalsClick, onAddComponentClick, onPreviewComponentClick, onRemoveComponentClick }) {
  const {
    stage,
    smartGoalsExtracted,
    smartGoals,
    loadingGoals,
    extractingGoals,
    loadingComponents,
    sourceItems,
    targetItems,
    selectedSourceId,
    selectedTargetId,
    selectSource,
    selectTarget,
    mappingsForStage,
    establishMapping,
    removeMapping,
  } = tm;

  const mappedSourceCounts = countBy(mappingsForStage, 'sourceId');
  const mappedTargetCounts = countBy(mappingsForStage, 'targetId');

  const linkedTargetIds = new Set(
    mappingsForStage.filter((m) => m.sourceId === selectedSourceId).map((m) => m.targetId)
  );
  const linkedSourceIds = new Set(
    mappingsForStage.filter((m) => m.targetId === selectedTargetId).map((m) => m.sourceId)
  );

  const isProposalStage = stage.sourceType === 'PROPOSAL';

  return (
    <div className="stm-workspace">
      <section className="stm-column-wrap">
        {isProposalStage ? (
          <div className="stm-column">
            <div className="stm-column__header">
              <span>SMART Goals</span>
            </div>
            <SmartGoalPanel
              smartGoalsExtracted={smartGoalsExtracted}
              smartGoals={smartGoals}
              loading={loadingGoals}
              extracting={extractingGoals}
              selectedId={selectedSourceId}
              linkedIds={linkedSourceIds}
              mappedCounts={mappedSourceCounts}
              onSelect={selectSource}
              onExtractClick={onExtractGoalsClick}
            />
          </div>
        ) : (
          <ComponentLibraryColumn
            heading={`${DOC_TYPE_LABELS[stage.sourceType]} Components (Source)`}
            docType={stage.sourceType}
            items={sourceItems}
            loading={loadingComponents}
            selectedId={selectedSourceId}
            linkedIds={linkedSourceIds}
            mappedCounts={mappedSourceCounts}
            onSelect={selectSource}
            onAdd={() => onAddComponentClick(stage.sourceType)}
            onPreview={onPreviewComponentClick}
            onRemove={(id) => onRemoveComponentClick(stage.sourceType, id)}
            emptyHint={`Extract or add ${DOC_TYPE_LABELS[stage.sourceType]} components before establishing ${stage.label} mappings.`}
          />
        )}
      </section>

      <MappingControls
        stage={stage}
        sourceItems={sourceItems}
        targetItems={targetItems}
        selectedSourceId={selectedSourceId}
        selectedTargetId={selectedTargetId}
        mappingsForStage={mappingsForStage}
        onEstablish={establishMapping}
        onRemoveMapping={removeMapping}
      />

      <section className="stm-column-wrap">
        <ComponentLibraryColumn
          heading={`${DOC_TYPE_LABELS[stage.targetType]} Components (Target)`}
          docType={stage.targetType}
          items={targetItems}
          loading={loadingComponents}
          selectedId={selectedTargetId}
          linkedIds={linkedTargetIds}
          mappedCounts={mappedTargetCounts}
          onSelect={selectTarget}
          onAdd={() => onAddComponentClick(stage.targetType)}
          onPreview={onPreviewComponentClick}
          onRemove={(id) => onRemoveComponentClick(stage.targetType, id)}
          emptyHint={`No ${DOC_TYPE_LABELS[stage.targetType]} components are currently available. Extract or add components before establishing ${stage.label} mappings.`}
        />
      </section>
    </div>
  );
}

export default MappingWorkspace;
