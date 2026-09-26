import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  getSmartGoals,
  getLatestMappingActivity,
  extractSmartGoalsFromProposal,
  getTraceComponents,
  extractTraceComponents,
  createTraceComponent,
  deleteTraceComponent,
  getStagedMappings,
  addStagedMappings,
  removeStagedMapping,
  syncStagedMappings,
} from '../api';
import { notifyMappingsChanged, useMappingsChangedRefresh } from '../utils/mappingEvents';
import { getEvaluationHistory } from '../../api';
import { extractSubmissionMeta } from '../../utils/dashboardUtils';
import { STAGES, COMPONENT_DOC_TYPES, artifactKindLabel, formatArtifactKind, groupGoalsIntoClusters } from '../constants';

/**
 * The staged mapping chain (stage, sourceId, targetId) is shared per team on the server, so
 * every teacher and student sees the same workspace. Save Mapping asks the server to turn
 * the chain into the GoalComponentMapping rows that continuity detection, readiness
 * scoring, audit export and the results matrix read.
 *
 * Before the workspace was shared it lived in each browser's localStorage. The first time
 * a team's shared workspace is empty, this browser's old chain for that team is uploaded
 * so earlier work isn't lost.
 */
const LEGACY_MAPPINGS_STORAGE_KEY = 'synctrace.stagedMappings';
const LEGACY_SEEDED_KEY = 'synctrace.stagedMappings.seeded';

function loadLegacyMappings() {
  try {
    const parsed = JSON.parse(localStorage.getItem(LEGACY_MAPPINGS_STORAGE_KEY) || '[]');
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function legacySeedDone(teamCode) {
  try {
    return JSON.parse(localStorage.getItem(LEGACY_SEEDED_KEY) || '[]').includes(teamCode.toUpperCase());
  } catch {
    return true;
  }
}

function markLegacySeedDone(teamCode) {
  try {
    const done = JSON.parse(localStorage.getItem(LEGACY_SEEDED_KEY) || '[]');
    localStorage.setItem(LEGACY_SEEDED_KEY, JSON.stringify([...new Set([...done, teamCode.toUpperCase()])]));
  } catch {
    // localStorage unavailable — nothing to seed from anyway.
  }
}

function toWorkspaceMapping(row) {
  return { id: row.id, stage: row.stage, sourceId: row.sourceId, targetId: row.targetId };
}

/** A component id can be a target in any stage and a source in any stage but PROPOSAL_SRS. */
function referencesComponent(mapping, componentId) {
  return mapping.targetId === componentId
    || (mapping.sourceId === componentId && mapping.stage !== 'PROPOSAL_SRS');
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
 * Staged traceability workspace. SMART goals, components and the source-to-target
 * mapping chain are all shared, backend-persisted data for the team; saveMapping()
 * publishes the chain to the goal-to-component links the results read.
 */
export function useStagedTraceability(showToast, teamCode) {
  const [selectedStage, setSelectedStageState] = useState(STAGES[0].key);
  const [selectedSourceId, setSelectedSourceId] = useState(null);
  const [selectedTargetIds, setSelectedTargetIds] = useState(() => new Set());

  const [smartGoals, setSmartGoals] = useState([]);
  const [loadingGoals, setLoadingGoals] = useState(false);
  const [extractingGoals, setExtractingGoals] = useState(false);

  const [components, setComponents] = useState(emptyComponentLibrary);
  const [loadingComponents, setLoadingComponents] = useState(false);
  const [extractingComponents, setExtractingComponents] = useState(false);

  const [mappings, setMappings] = useState([]);
  const [mappingsLoaded, setMappingsLoaded] = useState(false);

  const [saveState, setSaveState] = useState('idle'); // idle | saving | verifying
  const [verification, setVerification] = useState({ status: 'idle', message: null, verifiedAt: null });
  const [lastError, setLastError] = useState(null);
  const [mappingActivity, setMappingActivity] = useState(null);

  const loadMappingActivity = useCallback(async () => {
    if (!teamCode) {
      setMappingActivity(null);
      return;
    }
    try {
      setMappingActivity(await getLatestMappingActivity(teamCode));
    } catch {
      setMappingActivity(null);
    }
  }, [teamCode]);

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
        getTraceComponents(undefined, undefined, teamCode),
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
    loadMappingActivity();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [teamCode]);

  const loadMappings = useCallback(async () => {
    if (!teamCode) {
      setMappings([]);
      setMappingsLoaded(false);
      return;
    }
    try {
      const rows = await getStagedMappings(teamCode);
      setMappings(rows.map(toWorkspaceMapping));
      setMappingsLoaded(true);
    } catch (err) {
      showToast?.(err.message, 'error');
    }
  }, [teamCode, showToast]);

  useEffect(() => {
    setMappingsLoaded(false);
    loadMappings();
  }, [loadMappings]);

  // Edits and saves by other users (or other tabs) show up without a reload.
  useMappingsChangedRefresh(teamCode, loadMappings, { pollMs: 30000 });

  // One-time upload of this browser's pre-sharing chain into an empty shared workspace.
  useEffect(() => {
    if (!teamCode || !mappingsLoaded || mappings.length > 0 || loadingGoals || loadingComponents) return;
    if (legacySeedDone(teamCode)) return;
    const goalIds = new Set(groupGoalsIntoClusters(smartGoals).map((cluster) => cluster.id));
    const componentIds = new Set(Object.values(components).flat().map((c) => c.id));
    const known = (id, isGoal) => (isGoal ? goalIds.has(id) : componentIds.has(id));
    const legacy = loadLegacyMappings().filter((m) => STAGES.some((s) => s.key === m.stage)
      && known(m.sourceId, m.stage === 'PROPOSAL_SRS') && known(m.targetId, false));
    markLegacySeedDone(teamCode);
    if (legacy.length === 0) return;
    Promise.allSettled(legacy.map((m) => addStagedMappings(teamCode, [
      { stage: m.stage, sourceId: m.sourceId, targetId: m.targetId },
    ]))).then(() => {
      loadMappings();
      showToast?.('Your earlier mapping work was added to the shared workspace. Save Mapping to publish it.', 'info');
    });
  }, [teamCode, mappingsLoaded, mappings.length, loadingGoals, loadingComponents, smartGoals, components, loadMappings, showToast]);

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
    setSelectedTargetIds(new Set());
  }

  function selectSource(id) {
    setSelectedSourceId((prev) => (prev === id ? null : id));
  }

  function selectTarget(id) {
    setSelectedTargetIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function establishMapping() {
    if (!selectedSourceId || selectedTargetIds.size === 0) return;
    if (!teamCode) {
      showToast?.('Select a team before mapping.', 'error');
      return;
    }
    const newMappings = [...selectedTargetIds]
      .filter((targetId) => !mappingsForStage.some(
        (m) => m.sourceId === selectedSourceId && m.targetId === targetId
      ))
      .map((targetId) => ({ stage: selectedStage, sourceId: selectedSourceId, targetId }));
    if (newMappings.length === 0) {
      showToast?.('These components are already mapped.', 'info');
      return;
    }
    try {
      const rows = await addStagedMappings(teamCode, newMappings);
      setMappings((prev) => {
        const known = new Set(prev.map((m) => m.id));
        return [...prev, ...rows.filter((r) => !known.has(r.id)).map(toWorkspaceMapping)];
      });
      setSelectedTargetIds(new Set());
      notifyMappingsChanged(teamCode);
      showToast?.(`${newMappings.length} mapping${newMappings.length === 1 ? '' : 's'} established.`, 'success');
    } catch (err) {
      showToast?.(err.message || 'Failed to establish mapping.', 'error');
    }
  }

  async function removeMapping(mappingId) {
    const removed = mappings.find((m) => m.id === mappingId);
    setMappings((prev) => prev.filter((m) => m.id !== mappingId));
    try {
      await removeStagedMapping(teamCode, mappingId);
      notifyMappingsChanged(teamCode);
    } catch (err) {
      if (removed) setMappings((prev) => [...prev, removed]);
      showToast?.(err.message || 'Failed to remove mapping.', 'error');
    }
  }

  async function removeComponent(docType, componentId) {
    try {
      await deleteTraceComponent(componentId);
      setComponents((prev) => ({
        ...prev,
        [docType]: prev[docType].filter((c) => c.id !== componentId),
      }));
      // The server drops the component's links from the shared chain along with it.
      setMappings((prev) => prev.filter((m) => !referencesComponent(m, componentId)));
      notifyMappingsChanged(teamCode);
      if (selectedSourceId === componentId) setSelectedSourceId(null);
      if (selectedTargetIds.has(componentId)) {
        setSelectedTargetIds((prev) => {
          const next = new Set(prev);
          next.delete(componentId);
          return next;
        });
      }
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
    if (!teamCode) {
      const message = 'Select a team before saving the mapping.';
      showToast?.(message, 'error');
      return { ok: false, message };
    }

    setSaveState('saving');
    setLastError(null);
    try {
      // The server derives every goal link from the shared chain (all stages) and removes
      // links an earlier save produced that the chain no longer implies.
      await syncStagedMappings(teamCode, selectedStage);
      notifyMappingsChanged(teamCode);
      await loadMappingActivity();

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

      showToast?.(
        unmapped.length === 0
          ? 'Mapping saved. Traceability verified — full coverage.'
          : 'Mapping saved. Traceability verified — some gaps remain.',
        unmapped.length === 0 ? 'success' : 'info'
      );
      return { ok: true, verification: verified };
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
    selectedTargetIds,
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
    mappingActivity,
    saveMapping,
  };
}
