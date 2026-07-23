import { useCallback, useEffect, useState } from 'react';
import { getAllGoalComponents, getSmartGoals, getTraceComponents } from '../api';
import { DOC_TYPES } from '../constants';

export const WORKFLOW_STEPS = [
  {
    id: 'goals',
    number: 1,
    title: 'Extract goals',
    short: 'From the proposal',
    detail: 'Pull GENERAL and SPECIFIC SMART goals from a team proposal.',
    view: 'traceability',
    actionLabel: 'Extract goals',
  },
  {
    id: 'library',
    number: 2,
    title: 'Build library',
    short: 'Docs + GitHub code',
    detail: 'Extract SRS/SDD/SPMP/STD components, then ingest the team GitHub repo.',
    view: 'traceability',
    actionLabel: 'Open mapping',
  },
  {
    id: 'map',
    number: 3,
    title: 'Map goals',
    short: 'Link each goal',
    detail: 'Directly connect each goal to use cases, classes, tests, and code.',
    view: 'traceability',
    actionLabel: 'Continue mapping',
  },
  {
    id: 'review',
    number: 4,
    title: 'Review results',
    short: 'Check the matrix',
    detail: 'Open the matrix to see coverage gaps and fix anything missing.',
    view: 'traceability-results',
    actionLabel: 'View results',
  },
];

function computeProgress({ goals, componentCount, mappingsByGoal }) {
  const goalCount = Array.isArray(goals) ? goals.length : 0;
  const hasGoals = goalCount > 0;

  const mappedGoalCount = hasGoals
    ? goals.filter((g) => {
      const mapped = mappingsByGoal?.[g.id] || mappingsByGoal?.[String(g.id)] || [];
      if (mapped.length > 0) return true;
      return DOC_TYPES.some((dt) => g.categoryStatus?.[dt]);
    }).length
    : 0;

  const fullyCovered = hasGoals
    ? goals.filter((g) => {
      const mapped = mappingsByGoal?.[g.id] || mappingsByGoal?.[String(g.id)] || [];
      if (mapped.length > 0) {
        return DOC_TYPES.every((dt) => mapped.some((c) => c.docType === dt));
      }
      return DOC_TYPES.every((dt) => g.categoryStatus?.[dt]);
    }).length
    : 0;

  const hasLibrary = componentCount > 0;
  const hasMappings = mappedGoalCount > 0;

  let nextStepId = 'goals';
  if (hasGoals && !hasLibrary) nextStepId = 'library';
  else if (hasGoals && hasLibrary && !hasMappings) nextStepId = 'map';
  else if (hasGoals && hasMappings) nextStepId = 'review';

  const done = {
    goals: hasGoals,
    library: hasLibrary,
    map: hasMappings,
    review: fullyCovered > 0,
  };

  return {
    goalCount,
    componentCount,
    mappedGoalCount,
    fullyCovered,
    hasGoals,
    hasLibrary,
    hasMappings,
    nextStepId,
    done,
    loading: false,
  };
}

export function useSyncTraceProgress() {
  const [state, setState] = useState({
    goalCount: 0,
    componentCount: 0,
    mappedGoalCount: 0,
    fullyCovered: 0,
    hasGoals: false,
    hasLibrary: false,
    hasMappings: false,
    nextStepId: 'goals',
    done: { goals: false, library: false, map: false, review: false },
    loading: true,
  });

  const refresh = useCallback(async () => {
    setState((s) => ({ ...s, loading: true }));
    try {
      const [goals, components, mappingsByGoal] = await Promise.all([
        getSmartGoals().catch(() => []),
        getTraceComponents().catch(() => []),
        getAllGoalComponents().catch(() => ({})),
      ]);
      setState(computeProgress({
        goals: Array.isArray(goals) ? goals : [],
        componentCount: Array.isArray(components) ? components.length : 0,
        mappingsByGoal: mappingsByGoal || {},
      }));
    } catch {
      setState((s) => ({ ...s, loading: false }));
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { ...state, refresh };
}

export function getNextStep(nextStepId) {
  return WORKFLOW_STEPS.find((s) => s.id === nextStepId) || WORKFLOW_STEPS[0];
}
