import { useCallback, useEffect, useState } from 'react';
import {
  getSmartGoals,
  createSmartGoal,
  deleteSmartGoal,
  getGoalComponents,
  addGoalComponents,
  removeGoalComponent,
} from '../api';
import { DOC_TYPES } from '../constants';

export { DOC_TYPES };

export function useTraceability(showToast, initialGoalId = null) {
  const [goals, setGoals]                 = useState([]);
  const [loadingGoals, setLoadingGoals]    = useState(true);
  const [selectedGoalId, setSelectedGoalId] = useState(null);

  const [mappedComponents, setMappedComponents] = useState([]);
  const [loadingMappings, setLoadingMappings]    = useState(false);

  const loadGoals = useCallback(async (keepSelection = true) => {
    setLoadingGoals(true);
    try {
      const data = await getSmartGoals();
      setGoals(data);
      if (!keepSelection || (data.length > 0 && !data.some((g) => g.id === selectedGoalId))) {
        const preferred = !keepSelection && data.some((g) => g.id === initialGoalId) ? initialGoalId : data[0]?.id ?? null;
        setSelectedGoalId(preferred);
      }
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoadingGoals(false);
    }
  }, [selectedGoalId, showToast, initialGoalId]);

  useEffect(() => {
    loadGoals(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadMappings = useCallback(async (goalId) => {
    if (!goalId) {
      setMappedComponents([]);
      return;
    }
    setLoadingMappings(true);
    try {
      setMappedComponents(await getGoalComponents(goalId));
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoadingMappings(false);
    }
  }, [showToast]);

  useEffect(() => {
    loadMappings(selectedGoalId);
  }, [selectedGoalId, loadMappings]);

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
      await addGoalComponents(selectedGoalId, componentIds);
      await loadMappings(selectedGoalId);
      await loadGoals(true);
      showToast?.(`${componentIds.length} component(s) mapped.`, 'success');
    } catch (err) {
      showToast?.(err.message, 'error');
    }
  }

  async function handleRemoveComponent(componentId) {
    if (!selectedGoalId) return;
    try {
      await removeGoalComponent(selectedGoalId, componentId);
      await loadMappings(selectedGoalId);
      await loadGoals(true);
    } catch (err) {
      showToast?.(err.message, 'error');
    }
  }

  const selectedGoal = goals.find((g) => g.id === selectedGoalId) || null;
  const generalGoals = goals.filter((g) => g.goalKind === 'GENERAL');

  return {
    goals,
    generalGoals,
    loadingGoals,
    selectedGoalId,
    setSelectedGoalId,
    selectedGoal,
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
