import { useEffect, useState } from 'react';
import { CircleCheck, Plus, TriangleAlert } from 'lucide-react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import AddComponentModal from '../../components/teacher/AddComponentModal';
import CategoryCard from '../../components/teacher/CategoryCard';
import ExtractComponentsModal from '../../components/teacher/ExtractComponentsModal';
import ExtractGoalsModal from '../../components/teacher/ExtractGoalsModal';
import NewGoalForm from '../../components/teacher/NewGoalForm';
import ComponentDetailModal from '../../components/teacher/ComponentDetailModal';
import { useTraceability, DOC_TYPES } from '../../hooks/useTraceability';
import { useSelectedTeam } from '../../hooks/useSelectedTeam';
import { orderGoalsHierarchically, preferredArtifactHint } from '../../constants';
import TeamSelect from '../../components/common/TeamSelect';
import './TraceabilityMappingPage.css';

function TraceabilityMappingPage({
  initialGoalId = null,
  focusStep,
  focusDocType = null,
  onProgressRefresh,
}) {
  const { toast, showToast, hideToast } = useToast();
  const [selectedTeam, setSelectedTeam] = useSelectedTeam();
  const tm = useTraceability(showToast, initialGoalId, selectedTeam);

  const [showNewGoalForm, setShowNewGoalForm] = useState(false);
  const [modalDocType, setModalDocType] = useState('SRS');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isExtractModalOpen, setIsExtractModalOpen] = useState(false);
  const [isExtractGoalsModalOpen, setIsExtractGoalsModalOpen] = useState(false);
  const [previewComponent, setPreviewComponent] = useState(null);

  useEffect(() => {
    if (focusStep === 'goals') setIsExtractGoalsModalOpen(true);
    if (focusStep === 'library') setIsExtractModalOpen(true);
    if (focusStep === 'map' && focusDocType) {
      setModalDocType(focusDocType);
      setIsModalOpen(true);
    }
  }, [focusStep, focusDocType]);

  function openAddModal(docType) {
    setModalDocType(docType);
    setIsModalOpen(true);
  }

  async function handleCreateGoal(description, options) {
    const goal = await tm.createGoal(description, options);
    if (goal) {
      setShowNewGoalForm(false);
      onProgressRefresh?.();
    }
  }

  async function handleGoalsExtracted() {
    await tm.loadGoals();
    setIsExtractGoalsModalOpen(false);
    onProgressRefresh?.();
  }

  async function refreshGoalsAndMappings() {
    await tm.loadGoals(true);
    if (tm.selectedGoalId) {
      await tm.loadMappings(tm.selectedGoalId);
    }
    onProgressRefresh?.();
  }

  const mappedByType = DOC_TYPES.reduce((acc, dt) => {
    acc[dt] = tm.mappedComponents.filter((c) => c.docType === dt);
    return acc;
  }, {});

  const orderedGoals = orderGoalsHierarchically(tm.goals);
  const goalIndexById = new Map(orderedGoals.map((g, i) => [g.id, i]));
  const mappingHint = preferredArtifactHint(tm.selectedGoal?.goalKind || 'SPECIFIC');

  return (
    <div className="tm-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Goals & Mapping"
        subtitle="Extract SMART goals and link them to document and code components"
        actions={
          <div className="teacher-header-actions">
            <button className="btn btn--soft tm-link-btn" onClick={() => setIsExtractGoalsModalOpen(true)}>
              Extract Goals
            </button>
            <button className="btn btn--primary tm-link-btn" onClick={() => setIsExtractModalOpen(true)}>
              Extract Components
            </button>
          </div>
        }
      />

      <div className="tm-team-filter-row">
        <TeamSelect value={selectedTeam} onChange={setSelectedTeam} />
      </div>

      <div className="tm-layout">
        {/* ── Goal sidebar ─────────────────────────────────────────────────── */}
        <aside className="tm-sidebar">
          <div className="tm-sidebar__header">
            <span>SMART GOALS</span>
            <button className="tm-icon-btn" title="Add goal" onClick={() => setShowNewGoalForm((v) => !v)}>
              <Plus size={14} />
            </button>
          </div>

          {showNewGoalForm && (
            <NewGoalForm
              onCreate={handleCreateGoal}
              onCancel={() => setShowNewGoalForm(false)}
              generalGoals={tm.generalGoals}
              defaultTeamCode={selectedTeam}
            />
          )}

          {tm.loadingGoals ? (
            <p className="tm-muted" style={{ padding: '0 0.25rem' }}>Loading goals...</p>
          ) : orderedGoals.length === 0 ? (
            <div className="tm-sidebar-empty">
              <p className="tm-muted">{selectedTeam ? `No goals yet for ${selectedTeam}.` : 'No goals yet.'}</p>
              <button type="button" className="btn btn--primary" style={{ width: '100%' }} onClick={() => setIsExtractGoalsModalOpen(true)}>
                Extract from proposal
              </button>
            </div>
          ) : (
            <div className="tm-goal-list">
              {orderedGoals.map((g) => {
                const allComplete = DOC_TYPES.every((dt) => g.categoryStatus?.[dt]);
                const idx = goalIndexById.get(g.id) ?? 0;
                const isChild = g.goalKind !== 'GENERAL' && Boolean(g.parentGoalId);
                return (
                  <button
                    key={g.id}
                    className={`tm-goal-item ${isChild ? 'tm-goal-item--child' : ''} ${tm.selectedGoalId === g.id ? 'tm-goal-item--active' : ''}`}
                    onClick={() => tm.setSelectedGoalId(g.id)}
                  >
                    <span className="tm-goal-item__code">G{idx + 1}</span>
                    <span className="tm-goal-item__text">
                      <span className={`tm-goal-kind tm-goal-kind--${(g.goalKind || 'SPECIFIC').toLowerCase()}`}>
                        {g.goalKind === 'GENERAL' ? 'GEN' : 'SPEC'}
                      </span>
                      {g.description}
                    </span>
                    <span className={`tm-status-icon ${allComplete ? 'tm-status-icon--ok' : 'tm-status-icon--warn'}`}>
                      {allComplete ? <CircleCheck size={16} /> : <TriangleAlert size={16} />}
                    </span>
                  </button>
                );
              })}
            </div>
          )}

          <div className="tm-sidebar__spacer" />
          <button className="pw-btn pw-btn--primary" style={{ width: '100%' }} onClick={() => showToast('Mapping saved.', 'success')}>
            Save Mapping
          </button>
        </aside>

        {/* ── Main content ────────────────────────────────────────────────── */}
        <main className="tm-main">
          {tm.selectedGoal ? (
            <>
              <section className="tm-card tm-goal-summary">
                <span className="tm-muted tm-eyebrow">TRACEABILITY CENTER POINT</span>
                <h3 className="pw-card__title">
                  G{(goalIndexById.get(tm.selectedGoal.id) ?? 0) + 1} — {tm.selectedGoal.description}
                </h3>
                <div className="tm-goal-meta">
                  <span className={`tm-goal-kind tm-goal-kind--${(tm.selectedGoal.goalKind || 'SPECIFIC').toLowerCase()}`}>
                    {tm.selectedGoal.goalKind === 'GENERAL' ? 'General objective → modules' : 'Specific objective → functions/transactions'}
                  </span>
                  {tm.selectedGoal.parentGoalId && (
                    <span className="tm-goal-team">
                      under G{(goalIndexById.get(tm.selectedGoal.parentGoalId) ?? -1) + 1 || '?'}
                    </span>
                  )}
                  {tm.selectedGoal.teamCode && (
                    <span className="tm-goal-team">{tm.selectedGoal.teamCode}</span>
                  )}
                </div>
                <p className="tm-muted tm-mapping-hint">{mappingHint}</p>
              </section>

              {tm.loadingMappings ? (
                <p className="tm-muted">Loading mappings...</p>
              ) : (
                <div className="tm-grid">
                  {DOC_TYPES.map((dt) => (
                    <CategoryCard
                      key={dt}
                      docType={dt}
                      components={mappedByType[dt]}
                      complete={mappedByType[dt].length > 0}
                      onAdd={() => openAddModal(dt)}
                      onRemove={tm.removeComponent}
                      onComponentClick={setPreviewComponent}
                    />
                  ))}
                </div>
              )}
            </>
          ) : (
            <div className="empty-state">
              <p><strong>Select a goal on the left</strong> to map its SRS, SDD, SPMP, STD, and Implementation components.</p>
              {orderedGoals.length === 0 && (
                <button type="button" className="btn btn--primary" onClick={() => setIsExtractGoalsModalOpen(true)}>
                  Start: Extract goals from proposal
                </button>
              )}
            </div>
          )}
        </main>
      </div>

      <AddComponentModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        initialDocType={modalDocType}
        excludeComponentIds={tm.mappedComponents.map((c) => c.id)}
        showToast={showToast}
        teamCode={selectedTeam}
        onComponentRenamed={refreshGoalsAndMappings}
        onAddSelected={async (componentIds) => {
          await tm.addComponents(componentIds);
          setIsModalOpen(false);
          onProgressRefresh?.();
        }}
      />

      <ExtractComponentsModal
        isOpen={isExtractModalOpen}
        onClose={() => {
          setIsExtractModalOpen(false);
          onProgressRefresh?.();
        }}
        showToast={showToast}
        teamCode={selectedTeam}
      />

      <ExtractGoalsModal
        isOpen={isExtractGoalsModalOpen}
        onClose={() => setIsExtractGoalsModalOpen(false)}
        showToast={showToast}
        onExtracted={handleGoalsExtracted}
        teamCode={selectedTeam}
      />

      <ComponentDetailModal
        component={previewComponent}
        onClose={() => setPreviewComponent(null)}
        showToast={showToast}
        onRenamed={(updated) => {
          setPreviewComponent(updated);
          refreshGoalsAndMappings();
        }}
      />
    </div>
  );
}

export default TraceabilityMappingPage;
