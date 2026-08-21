import { useCallback, useEffect, useState } from 'react';
import {
  getSmartGoals,
  createSmartGoal,
  deleteSmartGoal,
  getGoalComponents,
  addGoalComponents,
  removeGoalComponent,
} from '../api';
import { DOC_TYPES, groupGoalsIntoClusters } from '../constants';

export { DOC_TYPES };

export function useTraceability(showToast, initialGoalId = null, teamCode = '') {
  const [goals, setGoals]                 = useState([]);
  const [loadingGoals, setLoadingGoals]    = useState(true);
  const [selectedGoalId, setSelectedGoalId] = useState(null);

  const [mappedComponents, setMappedComponents] = useState([]);
  const [loadingMappings, setLoadingMappings]    = useState(false);

  const loadGoals = useCallback(async (keepSelection = true) => {
    setLoadingGoals(true);
    try {
      const data = await getSmartGoals(teamCode || undefined);
      setGoals(data);

      // Selection always tracks a cluster's canonical (primary) id, never a
      // SPECIFIC child's own id — resolve any id to its cluster's id first.
      const clustersForData = groupGoalsIntoClusters(data);
      const clusterIdFor = (id) => {
        if (id == null) return null;
        const cluster = clustersForData.find(
          (c) => c.id === id || c.children.some((child) => child.id === id)
        );
        return cluster ? cluster.id : null;
      };

      const resolvedSelection = keepSelection ? clusterIdFor(selectedGoalId) : null;
      if (resolvedSelection) {
        if (resolvedSelection !== selectedGoalId) setSelectedGoalId(resolvedSelection);
      } else {
        const preferred = (!keepSelection && clusterIdFor(initialGoalId)) || clustersForData[0]?.id || null;
        setSelectedGoalId(preferred);
      }
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoadingGoals(false);
    }
  }, [selectedGoalId, showToast, initialGoalId, teamCode]);

  useEffect(() => {
    loadGoals(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [teamCode]);

  // A SMART Goal cluster (GENERAL objective + its SPECIFIC children, or a lone
  // orphan SPECIFIC) is mapped as one unit — components map to the cluster's
  // canonical (primary) goal id, and mapped components are the union across
  // every member of the cluster.
  function memberIdsFor(goalId) {
    if (!goalId) return [];
    const cluster = groupGoalsIntoClusters(goals).find((c) => c.id === goalId);
    return cluster ? [cluster.primary.id, ...cluster.children.map((c) => c.id)] : [goalId];
  }

  const loadMappings = useCallback(async (ids) => {
    const idList = (Array.isArray(ids) ? ids : [ids]).filter(Boolean);
    if (idList.length === 0) {
      setMappedComponents([]);
      return;
    }
    setLoadingMappings(true);
    try {
      const results = await Promise.all(idList.map((id) => getGoalComponents(id)));
      const merged = new Map();
      results.flat().forEach((c) => merged.set(c.id, c));
      setMappedComponents([...merged.values()]);
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoadingMappings(false);
    }
  }, [showToast]);

  useEffect(() => {
    loadMappings(memberIdsFor(selectedGoalId));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedGoalId, goals, loadMappings]);

  async function handleCreateGoal(description, options = {}) {
    try {
      const goal = await createSmartGoal(description, options);
      await loadGoals(true);
      setSelectedGoalId(goal.id);
      showToast?.('Goal created.', 'success');
      return goal;
    } catch (err) {
      showToast?.(err.message, 'error');
      return null;
    }
  }

  async function handleDeleteGoal(goalId) {
    try {
      await deleteSmartGoal(goalId);
      await loadGoals(false);
      showToast?.('Goal deleted.', 'success');
    } catch (err) {
      showToast?.(err.message, 'error');
    }
  }

  async function handleAddComponents(componentIds) {
    if (!selectedGoalId || componentIds.length === 0) return;
    try {
      // Components map to the cluster's canonical (GENERAL/primary) goal id —
      // the whole cluster is treated as one SMART Goal.
      await addGoalComponents(selectedGoalId, componentIds);
      await loadMappings(memberIdsFor(selectedGoalId));
      await loadGoals(true);
      showToast?.(`${componentIds.length} component(s) mapped.`, 'success');
    } catch (err) {
      showToast?.(err.message, 'error');
    }
  }

  async function handleRemoveComponent(componentId) {
    if (!selectedGoalId) return;
    try {
      // The component may be attached to any member of the cluster (union view),
      // so remove it from every member — a no-op on ids where it isn't mapped.
      const ids = memberIdsFor(selectedGoalId);
      await Promise.all(ids.map((id) => removeGoalComponent(id, componentId)));
      await loadMappings(ids);
      await loadGoals(true);
    } catch (err) {
      showToast?.(err.message, 'error');
    }
  }

  const clusters = groupGoalsIntoClusters(goals);
  const selectedCluster = clusters.find((c) => c.id === selectedGoalId) || null;
  const selectedGoal = selectedCluster?.primary || goals.find((g) => g.id === selectedGoalId) || null;
  const generalGoals = goals.filter((g) => g.goalKind === 'GENERAL');

  return {
    goals,
    clusters,
    selectedCluster,
    generalGoals,
    loadingGoals,
    selectedGoalId,
    setSelectedGoalId,
    selectedGoal,
    memberIdsFor,
    mappedComponents,
    loadingMappings,
    loadGoals,
    loadMappings,
    createGoal: handleCreateGoal,
    deleteGoal: handleDeleteGoal,
    addComponents: handleAddComponents,
    removeComponent: handleRemoveComponent,
  };
}
