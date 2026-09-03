import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  getSmartGoals,
  extractSmartGoalsFromProposal,
  getTraceComponents,
  extractTraceComponents,
  createTraceComponent,
  deleteTraceComponent,
  addGoalComponents,
} from '../api';
import { getEvaluationHistory } from '../../api';
import { extractSubmissionMeta } from '../../utils/dashboardUtils';
import { STAGES, COMPONENT_DOC_TYPES, artifactKindLabel, formatArtifactKind, groupGoalsIntoClusters } from '../constants';

let localMappingCounter = 0;
function nextLocalMappingId() {
  localMappingCounter += 1;
  return `map-${Date.now().toString(36)}-${localMappingCounter}`;
}

/**
 * Traces a stage mapping's sourceId back through earlier stages to the SmartGoal
 * cluster id(s) it originates from. Stage chains only carry a real backend
 * meaning once resolved to a goal, since GoalComponentMapping is goal-to-component.
 */
function resolveGoalIdsForSource(sourceId, stageKey, mappingsList) {
  const currentStage = STAGES.find((s) => s.key === stageKey);
  if (!currentStage) return [];
  if (currentStage.sourceType === 'PROPOSAL') return [sourceId];

  const goalIds = new Set();
  STAGES
    .filter((s) => s.targetType === currentStage.sourceType)
    .forEach((precedingStage) => {
      mappingsList
        .filter((m) => m.stage === precedingStage.key && m.targetId === sourceId)
        .forEach((m) => {
          resolveGoalIdsForSource(m.sourceId, precedingStage.key, mappingsList)
            .forEach((id) => goalIds.add(id));
        });
    });
  return [...goalIds];
}

/**
 * The staged-workspace mapping chain (stage, sourceId, targetId) is a local
 * editing surface persisted to localStorage. Save Mapping resolves each stage
 * link back to its originating SmartGoal and persists it as a real
 * GoalComponentMapping row via addGoalComponents, which is what continuity
 * detection, readiness scoring, and audit export actually read.
 */
const MAPPINGS_STORAGE_KEY = 'synctrace.stagedMappings';

function loadStoredMappings() {
  try {
    const raw = localStorage.getItem(MAPPINGS_STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function persistMappings(mappings) {
  try {
    localStorage.setItem(MAPPINGS_STORAGE_KEY, JSON.stringify(mappings));
  } catch {
    // localStorage unavailable (e.g. private browsing) — mappings stay in-memory only.
  }
}

function emptyComponentLibrary() {
  return COMPONENT_DOC_TYPES.reduce((acc, dt) => {
    acc[dt] = [];
    return acc;
  }, {});
}

function computeStageStatus({ stage, sourceItems, targetItems, mappingsForStage }) {
  if (!stage) return 'Not Started';
  if (sourceItems.length === 0 || targetItems.length === 0) return 'Needs Attention';
  if (mappingsForStage.length === 0) return 'Not Started';
  const mappedSourceIds = new Set(mappingsForStage.map((m) => m.sourceId));
  const allSourcesMapped = sourceItems.every((s) => mappedSourceIds.has(s.id));
  return allSourcesMapped ? 'Mapped' : 'In Progress';
}

/**
 * Staged traceability workspace. SMART goals and components are real,
 * backend-persisted data (SmartGoal / TraceComponent). The source-to-target
 * mapping chain (stage, sourceId, targetId) is edited locally and persisted
 * to this browser's localStorage; saveMapping() is what pushes the resolved
 * goal-to-component links to the backend as real GoalComponentMapping rows.
 */
export function useStagedTraceability(showToast, teamCode) {
  const [selectedStage, setSelectedStageState] = useState(STAGES[0].key);
  const [selectedSourceId, setSelectedSourceId] = useState(null);
  const [selectedTargetId, setSelectedTargetId] = useState(null);

  const [smartGoals, setSmartGoals] = useState([]);
  const [loadingGoals, setLoadingGoals] = useState(false);
  const [extractingGoals, setExtractingGoals] = useState(false);

  const [components, setComponents] = useState(emptyComponentLibrary);
  const [loadingComponents, setLoadingComponents] = useState(false);
  const [extractingComponents, setExtractingComponents] = useState(false);

  const [mappings, setMappings] = useState(loadStoredMappings);

  const [saveState, setSaveState] = useState('idle'); // idle | saving | verifying
  const [verification, setVerification] = useState({ status: 'idle', message: null, verifiedAt: null });
  const [lastError, setLastError] = useState(null);

  const loadSmartGoals = useCallback(async () => {
    setLoadingGoals(true);
    try {
      const data = await getSmartGoals(teamCode || undefined);
      setSmartGoals(data);
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoadingGoals(false);
    }
  }, [teamCode, showToast]);

  const loadComponents = useCallback(async () => {
    setLoadingComponents(true);
    try {
      const [allComponents, history] = await Promise.all([
        getTraceComponents(),
        getEvaluationHistory().catch(() => []),
      ]);
      const historyTeamMap = new Map(
        history.map((h) => [h.id, extractSubmissionMeta(h.fileName).teamCode])
      );

      const next = emptyComponentLibrary();
      allComponents.forEach((c) => {
        if (!next[c.docType]) return; // PROPOSAL components live in SmartGoals, not here
        const ownerTeam = c.sourceHistoryId ? historyTeamMap.get(c.sourceHistoryId) : null;
        if (teamCode && ownerTeam && ownerTeam.toUpperCase() !== teamCode.toUpperCase()) return;
        next[c.docType].push({
          id: c.id,
          docType: c.docType,
          name: c.name,
          type: artifactKindLabel(c.artifactKind) || formatArtifactKind(c.artifactKind) || 'Component',
          description: c.content || '',
          codeName: c.codeName || '',
          origin: c.aiExtracted ? 'extracted' : 'manual',
        });
      });
      setComponents(next);
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoadingComponents(false);
    }
  }, [teamCode, showToast]);

  useEffect(() => {
    loadSmartGoals();
    loadComponents();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [teamCode]);

  useEffect(() => {
    persistMappings(mappings);
  }, [mappings]);

  const stage = useMemo(() => STAGES.find((s) => s.key === selectedStage) || STAGES[0], [selectedStage]);

  // Each doc type is produced as a target by exactly one earlier stage. A component only
  // becomes a valid source once it's been mapped as a target in that preceding stage.
  const precedingStage = useMemo(
    () => STAGES.find((s) => s.targetType === stage.sourceType) || null,
    [stage]
  );

  // A GENERAL objective and its SPECIFIC children are treated as one SMART Goal:
  // only the cluster (keyed by the GENERAL/orphan-SPECIFIC id) is a mappable source.
  const sourceItems = useMemo(() => {
    if (stage.sourceType === 'PROPOSAL') {
      return groupGoalsIntoClusters(smartGoals).map((cluster) => ({
        id: cluster.id,
        description: cluster.primary.description,
        goalKind: cluster.primary.goalKind,
      }));
    }
    const library = components[stage.sourceType] || [];
    if (!precedingStage) return library;
    const mappedIds = new Set(
      mappings.filter((m) => m.stage === precedingStage.key).map((m) => m.targetId)
    );
    return library.filter((item) => mappedIds.has(item.id));
  }, [stage, precedingStage, smartGoals, components, mappings]);

  const targetItems = useMemo(() => components[stage.targetType] || [], [stage, components]);

  const mappingsForStage = useMemo(
    () => mappings.filter((m) => m.stage === selectedStage),
    [mappings, selectedStage]
  );

  const stageStatus = useMemo(
    () => computeStageStatus({ stage, sourceItems, targetItems, mappingsForStage }),
    [stage, sourceItems, targetItems, mappingsForStage]
  );

  function setSelectedStage(key) {
    setSelectedStageState(key);
    setSelectedSourceId(null);
    setSelectedTargetId(null);
  }

  function selectSource(id) {
    setSelectedSourceId((prev) => (prev === id ? null : id));
  }

  function selectTarget(id) {
    setSelectedTargetId((prev) => (prev === id ? null : id));
  }

  function establishMapping() {
    if (!selectedSourceId || !selectedTargetId) return;
    const alreadyMapped = mappingsForStage.some(
      (m) => m.sourceId === selectedSourceId && m.targetId === selectedTargetId
    );
    if (alreadyMapped) {
      showToast?.('These components are already mapped.', 'info');
      return;
    }
    setMappings((prev) => [
      ...prev,
      { id: nextLocalMappingId(), stage: selectedStage, sourceId: selectedSourceId, targetId: selectedTargetId },
    ]);
    setSelectedTargetId(null);
    showToast?.('Mapping established.', 'success');
  }

  function removeMapping(mappingId) {
    setMappings((prev) => prev.filter((m) => m.id !== mappingId));
  }

  async function removeComponent(docType, componentId) {
    try {
      await deleteTraceComponent(componentId);
      setComponents((prev) => ({
        ...prev,
        [docType]: prev[docType].filter((c) => c.id !== componentId),
      }));
      setMappings((prev) => prev.filter((m) => m.sourceId !== componentId && m.targetId !== componentId));
      if (selectedSourceId === componentId) setSelectedSourceId(null);
      if (selectedTargetId === componentId) setSelectedTargetId(null);
    } catch (err) {
      showToast?.(err.message || 'Failed to remove component.', 'error');
    }
  }

  async function extractSmartGoals(fileId, fileName) {
    setExtractingGoals(true);
    setLastError(null);
    try {
      const sessionId = crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2);
      const result = await extractSmartGoalsFromProposal(fileId, fileName, 'auto', sessionId, teamCode || undefined);
      await loadSmartGoals();
      showToast?.(
        result.count > 0 ? `Extracted ${result.count} SMART goal(s).` : 'No SMART goals found in this proposal.',
        result.count > 0 ? 'success' : 'info'
      );
      return true;
    } catch (err) {
      setLastError(err.message || 'Failed to extract SMART goals.');
      showToast?.(err.message || 'Failed to extract SMART goals.', 'error');
      return false;
    } finally {
      setExtractingGoals(false);
    }
  }

  async function extractComponents(historyIds) {
    if (!historyIds || historyIds.length === 0) return false;
    setExtractingComponents(true);
    setLastError(null);
    try {
      let totalFound = 0;
      let failed = 0;
      for (const historyId of historyIds) {
        try {
          const result = await extractTraceComponents(historyId);
          totalFound += result.count || 0;
        } catch {
          failed += 1;
        }
      }
      await loadComponents();

      if (failed === 0) {
        showToast?.(`Extracted ${totalFound} component(s) across ${historyIds.length} document(s).`, 'success');
      } else {
        showToast?.(
          `${historyIds.length - failed} of ${historyIds.length} document(s) extracted (${totalFound} component(s) found). ${failed} failed.`,
          'error'
        );
      }
      return failed < historyIds.length;
    } catch (err) {
      setLastError(err.message || 'Failed to extract components.');
      showToast?.(err.message || 'Failed to extract components.', 'error');
      return false;
    } finally {
      setExtractingComponents(false);
    }
  }

  async function addComponent(docType, data) {
    try {
      const created = await createTraceComponent(
        docType,
        data.name.trim(),
        (data.description || '').trim() || data.name.trim(),
        data.type,
        (data.codeName || '').trim() || undefined,
        data.imageData || undefined
      );
      await loadComponents();
      showToast?.(`${created.name} added to the ${docType} library.`, 'success');
      return created;
    } catch (err) {
      showToast?.(err.message || 'Failed to add component.', 'error');
      return null;
    }
  }

  async function saveMapping() {
    if (mappingsForStage.length === 0) {
      const message = `No mappings established for ${stage.label}. Create at least one mapping before saving.`;
      showToast?.(message, 'error');
      return { ok: false, message };
    }

    setSaveState('saving');
    setLastError(null);
    try {
      const goalComponentPairs = new Map(); // goalId -> Set<componentId>
      const addPair = (goalId, componentId) => {
        if (!goalComponentPairs.has(goalId)) goalComponentPairs.set(goalId, new Set());
        goalComponentPairs.get(goalId).add(componentId);
      };

      mappingsForStage.forEach((m) => {
        const goalIds = resolveGoalIdsForSource(m.sourceId, m.stage, mappings);
        goalIds.forEach((goalId) => {
          addPair(goalId, m.targetId);
          if (stage.sourceType !== 'PROPOSAL') addPair(goalId, m.sourceId);
        });
      });

      const persistResults = await Promise.allSettled(
        [...goalComponentPairs.entries()].map(([goalId, componentIds]) =>
          addGoalComponents(goalId, [...componentIds])
        )
      );
      const persistFailures = persistResults.filter((r) => r.status === 'rejected').length;

      setSaveState('verifying');

      const mappedSourceIds = new Set(mappingsForStage.map((m) => m.sourceId));
      const unmapped = sourceItems.filter((s) => !mappedSourceIds.has(s.id));
      const verified = {
        status: unmapped.length === 0 ? 'verified' : 'attention',
        message: unmapped.length === 0
          ? `All ${stage.label} source components are traced.`
          : `${unmapped.length} source component(s) in ${stage.label} are not yet mapped.`,
        verifiedAt: new Date().toISOString(),
      };
      setVerification(verified);

      if (persistFailures > 0) {
        showToast?.(
          `Mapping saved locally, but ${persistFailures} goal link(s) failed to sync to the server. Traceability results may be incomplete until you retry.`,
          'error'
        );
      } else {
        showToast?.(
          unmapped.length === 0
            ? 'Mapping saved. Traceability verified — full coverage.'
            : 'Mapping saved. Traceability verified — some gaps remain.',
          unmapped.length === 0 ? 'success' : 'info'
        );
      }
      return { ok: persistFailures === 0, verification: verified };
    } catch (err) {
      const message = err.message || 'Failed to save mapping.';
      setLastError(message);
      setVerification({ status: 'error', message, verifiedAt: null });
      showToast?.(message, 'error');
      return { ok: false, message };
    } finally {
      setSaveState('idle');
    }
  }

  return {
    stages: STAGES,
    stage,
    precedingStage,
    selectedStage,
    setSelectedStage,

    smartGoalsExtracted: smartGoals.length > 0,
    smartGoals,
    loadingGoals,
    extractingGoals,
    extractSmartGoals,

    components,
    loadingComponents,
    extractingComponents,
    extractComponents,
    addComponent,
    removeComponent,
    refreshComponents: loadComponents,

    sourceItems,
    targetItems,
    selectedSourceId,
    selectedTargetId,
    selectSource,
    selectTarget,

    mappings,
    mappingsForStage,
    establishMapping,
    removeMapping,

    stageStatus,
    saveState,
    verification,
    lastError,
    saveMapping,
  };
}
