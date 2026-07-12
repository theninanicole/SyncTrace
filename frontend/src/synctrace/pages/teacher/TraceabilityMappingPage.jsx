import { useState } from 'react';
import { CircleCheck, Plus, Sparkles, TriangleAlert } from 'lucide-react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import AddComponentModal from '../../components/teacher/AddComponentModal';
import CategoryCard from '../../components/teacher/CategoryCard';
import ExtractComponentsModal from '../../components/teacher/ExtractComponentsModal';
import NewGoalForm from '../../components/teacher/NewGoalForm';
import { useTraceability, DOC_TYPES } from '../../hooks/useTraceability';
import './TraceabilityMappingPage.css';

function TraceabilityMappingPage({ initialGoalId = null }) {
  const { toast, showToast, hideToast } = useToast();
  const tm = useTraceability(showToast, initialGoalId);

  const [showNewGoalForm, setShowNewGoalForm] = useState(false);
  const [modalDocType, setModalDocType] = useState('ALL');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isExtractModalOpen, setIsExtractModalOpen] = useState(false);

  function openAddModal(docType) {
    setModalDocType(docType);
    setIsModalOpen(true);
  }

  async function handleCreateGoal(description) {
    const goal = await tm.createGoal(description);
    if (goal) setShowNewGoalForm(false);
  }

  const mappedByType = DOC_TYPES.reduce((acc, dt) => {
    acc[dt] = tm.mappedComponents.filter((c) => c.docType === dt);
    return acc;
  }, {});

  return (
    <div className="tm-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Traceability Mapping"
        subtitle="Map and verify goal continuity across engineering artifacts"
        actions={
          <div className="teacher-header-actions">
            <button className="btn btn--soft tm-link-btn" onClick={() => setIsExtractModalOpen(true)}>
              Extract Components
            </button>
          </div>
        }
      />

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
            <NewGoalForm onCreate={handleCreateGoal} onCancel={() => setShowNewGoalForm(false)} />
          )}

          {tm.loadingGoals ? (
            <p className="tm-muted" style={{ padding: '0 0.25rem' }}>Loading goals...</p>
          ) : tm.goals.length === 0 ? (
            <p className="tm-muted" style={{ padding: '0 0.25rem' }}>No goals yet. Add one to get started.</p>
          ) : (
            <div className="tm-goal-list">
              {tm.goals.map((g, idx) => {
                const allComplete = DOC_TYPES.every((dt) => g.categoryStatus?.[dt]);
                return (
                  <button
                    key={g.id}
                    className={`tm-goal-item ${tm.selectedGoalId === g.id ? 'tm-goal-item--active' : ''}`}
                    onClick={() => tm.setSelectedGoalId(g.id)}
                  >
                    <span className="tm-goal-item__code">G{idx + 1}</span>
                    <span className="tm-goal-item__text">{g.description}</span>
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
                  G{tm.goals.findIndex((g) => g.id === tm.selectedGoal.id) + 1} — {tm.selectedGoal.description}
                </h3>
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
                    />
                  ))}
                </div>
              )}
            </>
          ) : (
            <div className="empty-state">
              <p>Select or create a SMART goal to begin mapping.</p>
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
        onAddSelected={async (componentIds) => {
          await tm.addComponents(componentIds);
          setIsModalOpen(false);
        }}
      />

      <ExtractComponentsModal
        isOpen={isExtractModalOpen}
        onClose={() => setIsExtractModalOpen(false)}
        showToast={showToast}
      />
    </div>
  );
}

export default TraceabilityMappingPage;
