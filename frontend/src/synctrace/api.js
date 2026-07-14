// ── SyncTrace: Traceability Mapping ─────────────────────────────────────────
// Mock data only

const DOC_TYPES = ['SRS', 'SDD', 'SPMP', 'STD', 'IMPLEMENTATION'];

const delay = (ms = 250) => new Promise((resolve) => setTimeout(resolve, ms));

let nextGoalId = 4;
let nextComponentId = 7;

const goals = [
  { id: 1, description: 'Enable automated submission management via Google Sheets integration by end of development', createdAt: '2026-05-01T09:00:00' },
  { id: 2, description: 'Achieve 95% test coverage on the evaluation scoring engine before the SPMP milestone', createdAt: '2026-05-03T09:00:00' },
  { id: 3, description: 'Deliver a responsive teacher dashboard with real-time submission status by SDD review', createdAt: '2026-05-05T09:00:00' },
];

const components = [
  { id: 1, docType: 'SRS', name: 'FR-12 Google Sheets Sync', content: 'The system shall synchronize roster data with the linked Google Sheet on a five-minute interval.', sourceHistoryId: null, imageData: null, aiExtracted: false, createdAt: '2026-05-01T10:00:00' },
  { id: 2, docType: 'SDD', name: 'SheetsSyncService class diagram', content: 'Class diagram detailing SheetsSyncService and its collaborators.', sourceHistoryId: null, imageData: null, aiExtracted: true, createdAt: '2026-05-02T11:00:00' },
  { id: 3, docType: 'SPMP', name: 'Sprint 3 milestone: Sheets integration', content: 'Milestone covering delivery of the sheets sync feature.', sourceHistoryId: null, imageData: null, aiExtracted: false, createdAt: '2026-05-02T12:00:00' },
  { id: 4, docType: 'STD', name: 'TC-045 Roster sync round trip', content: 'Verifies roster changes made in Sheets propagate back to the roster within 5 minutes.', sourceHistoryId: null, imageData: null, aiExtracted: false, createdAt: '2026-05-03T09:00:00' },
  { id: 5, docType: 'IMPLEMENTATION', name: 'SheetsSyncService.java', content: 'Implementation of the periodic sync polling loop.', sourceHistoryId: null, imageData: null, aiExtracted: true, createdAt: '2026-05-03T10:00:00' },
  { id: 6, docType: 'SDD', name: 'ScoringEngine sequence diagram', content: 'Sequence diagram for the automated scoring pipeline.', sourceHistoryId: null, imageData: null, aiExtracted: false, createdAt: '2026-05-04T09:00:00' },
];

const mappings = [
  { goalId: 1, componentId: 1 },
  { goalId: 1, componentId: 2 },
  { goalId: 1, componentId: 3 },
  { goalId: 1, componentId: 4 },
  { goalId: 1, componentId: 5 },
  { goalId: 2, componentId: 6 },
];

function computeCategoryStatus(goalId) {
  const status = {};
  DOC_TYPES.forEach((dt) => { status[dt] = false; });
  mappings
    .filter((m) => m.goalId === goalId)
    .forEach((m) => {
      const component = components.find((c) => c.id === m.componentId);
      if (component) status[component.docType] = true;
    });
  return status;
}

function toSummary(component) {
  const { id, docType, name, sourceHistoryId, createdAt } = component;
  return { id, docType, name, sourceHistoryId, createdAt };
}

export const getSmartGoals = async () => {
  await delay();
  return goals.map((g) => ({ ...g, categoryStatus: computeCategoryStatus(g.id) }));
};

export const createSmartGoal = async (description) => {
  await delay();
  const goal = { id: nextGoalId++, description, createdAt: new Date().toISOString() };
  goals.push(goal);
  return goal;
};

export const deleteSmartGoal = async (goalId) => {
  await delay();
  const idx = goals.findIndex((g) => g.id === goalId);
  if (idx === -1) throw new Error('Goal not found.');
  goals.splice(idx, 1);
  for (let i = mappings.length - 1; i >= 0; i--) {
    if (mappings[i].goalId === goalId) mappings.splice(i, 1);
  }
  return { message: 'Goal deleted.' };
};

export const getTraceComponents = async (docType, search) => {
  await delay();
  return components
    .filter((c) => !docType || c.docType === docType)
    .filter((c) => !search || c.name.toLowerCase().includes(search.toLowerCase()))
    .slice()
    .sort((a, b) => b.id - a.id);
};

export const createTraceComponent = async (docType, name, content) => {
  await delay();
  const existing = components.find((c) => c.docType === docType && c.name.toLowerCase() === name.toLowerCase());
  if (existing) return existing;
  const component = {
    id: nextComponentId++,
    docType,
    name,
    content: content || null,
    sourceHistoryId: null,
    imageData: null,
    aiExtracted: false,
    createdAt: new Date().toISOString(),
  };
  components.push(component);
  return component;
};

export const renameTraceComponent = async (componentId, name) => {
  await delay();
  const component = components.find((c) => c.id === componentId);
  if (!component) throw new Error('Component not found.');
  const duplicate = components.find(
    (c) => c.id !== componentId && c.docType === component.docType && c.name.toLowerCase() === name.toLowerCase()
  );
  if (duplicate) throw new Error(`Another ${component.docType} component already has that name.`);
  component.name = name;
  return component;
};

export const deleteTraceComponent = async (componentId) => {
  await delay();
  const idx = components.findIndex((c) => c.id === componentId);
  if (idx === -1) throw new Error('Component not found.');
  components.splice(idx, 1);
  for (let i = mappings.length - 1; i >= 0; i--) {
    if (mappings[i].componentId === componentId) mappings.splice(i, 1);
  }
  return { message: 'Component deleted.' };
};

const EXTRACTION_SAMPLES = [
  { docType: 'SDD', name: 'Auto-extracted class diagram' },
  { docType: 'IMPLEMENTATION', name: 'Auto-extracted implementation snippet' },
];

export const extractTraceComponents = async (historyId) => {
  await delay(600);
  const extracted = EXTRACTION_SAMPLES.map((sample) => ({
    id: nextComponentId++,
    docType: sample.docType,
    name: `${sample.name} #${historyId}`,
    content: 'Extracted from the diagram analysis section of the evaluated submission.',
    sourceHistoryId: historyId,
    imageData: null,
    aiExtracted: true,
    createdAt: new Date().toISOString(),
  }));
  components.push(...extracted);
  return { components: extracted, count: extracted.length };
};

export const getGoalComponents = async (goalId) => {
  await delay();
  return mappings
    .filter((m) => m.goalId === goalId)
    .map((m) => components.find((c) => c.id === m.componentId))
    .filter(Boolean)
    .map(toSummary);
};

export const getAllGoalComponents = async () => {
  await delay();
  const byGoal = {};
  mappings.forEach((m) => {
    const component = components.find((c) => c.id === m.componentId);
    if (!component) return;
    if (!byGoal[m.goalId]) byGoal[m.goalId] = [];
    byGoal[m.goalId].push(toSummary(component));
  });
  return byGoal;
};

export const getTraceComponent = async (componentId) => {
  await delay();
  const component = components.find((c) => c.id === componentId);
  if (!component) throw new Error('Component not found.');
  return component;
};

export const addGoalComponents = async (goalId, componentIds) => {
  await delay();
  if (!goals.some((g) => g.id === goalId)) throw new Error('Goal not found.');
  let added = 0;
  componentIds.forEach((componentId) => {
    if (mappings.some((m) => m.goalId === goalId && m.componentId === componentId)) return;
    mappings.push({ goalId, componentId });
    added++;
  });
  return { added };
};

export const removeGoalComponent = async (goalId, componentId) => {
  await delay();
  const idx = mappings.findIndex((m) => m.goalId === goalId && m.componentId === componentId);
  if (idx !== -1) mappings.splice(idx, 1);
  return { message: 'Mapping removed.' };
};
