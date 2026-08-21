/** Shared SyncTrace taxonomy matching adviser requirements. */

export const DOC_TYPES = ['SRS', 'SDD', 'SPMP', 'STD', 'IMPLEMENTATION'];

export const GOAL_KINDS = [
  { value: 'GENERAL', label: 'General (→ modules)' },
  { value: 'SPECIFIC', label: 'Specific (→ functions/transactions)' },
];

/** The five fixed staged-mapping stages. Only one is worked on at a time. */
export const STAGES = [
  { key: 'PROPOSAL_SRS', label: 'Proposal → SRS', sourceType: 'PROPOSAL', targetType: 'SRS' },
  { key: 'SRS_SDD', label: 'SRS → SDD', sourceType: 'SRS', targetType: 'SDD' },
  { key: 'SRS_STD', label: 'SRS → STD', sourceType: 'SRS', targetType: 'STD' },
  { key: 'SRS_SPMP', label: 'SRS → SPMP', sourceType: 'SRS', targetType: 'SPMP' },
  { key: 'SDD_IMPLEMENTATION', label: 'SDD → Implementation', sourceType: 'SDD', targetType: 'IMPLEMENTATION' },
];

export const DOC_TYPE_LABELS = {
  PROPOSAL: 'Proposal',
  SRS: 'SRS',
  SDD: 'SDD',
  SPMP: 'SPMP',
  STD: 'STD',
  IMPLEMENTATION: 'Implementation',
};

/** Component-library document types (Proposal is handled via SMART Goals, not a component library). */
export const COMPONENT_DOC_TYPES = ['SRS', 'SDD', 'SPMP', 'STD', 'IMPLEMENTATION'];

export const ARTIFACT_KINDS_BY_DOC_TYPE = {
  SRS: [
    { value: 'USE_CASE', label: 'Use Case' },
    { value: 'ACTIVITY', label: 'Activity Diagram' },
    { value: 'WIREFRAME', label: 'Wireframe / UI Mockup' },
    { value: 'CONTEXT_DIAGRAM', label: 'Context Diagram' },
    { value: 'DATA_FLOW', label: 'Data Flow Diagram' },
    { value: 'OTHER_SRS', label: 'Other SRS Artifact' },
  ],
  SDD: [
    { value: 'CLASS', label: 'Class Diagram' },
    { value: 'SEQUENCE', label: 'Sequence Diagram' },
    { value: 'UI', label: 'UI Design' },
    { value: 'DATA_MODEL', label: 'Data Design (ERD / Schema)' },
    { value: 'NON_OO', label: 'Non-OO Component (Flowchart / Pseudocode)' },
    { value: 'OTHER_SDD', label: 'Other SDD Artifact' },
  ],
  SPMP: [
    { value: 'TASK', label: 'Task' },
    { value: 'DELIVERABLE', label: 'Deliverable' },
    { value: 'MILESTONE', label: 'Milestone' },
    { value: 'OTHER_SPMP', label: 'Other SPMP Artifact' },
  ],
  STD: [
    { value: 'TEST_DESIGN', label: 'Test Design' },
    { value: 'TEST_CASE', label: 'Test Case' },
    { value: 'TEST_LOG', label: 'Test Log' },
    { value: 'OTHER_STD', label: 'Other STD Artifact' },
  ],
  IMPLEMENTATION: [
    { value: 'IMPL_OO', label: 'Object-Oriented Code (Class)' },
    { value: 'IMPL_NON_OO', label: 'Non-OO Code (Function / Module)' },
    { value: 'OTHER_IMPLEMENTATION', label: 'Other Implementation Artifact' },
  ],
};

/** Label for an artifact kind using the adviser wording above. */
export function artifactKindLabel(kind) {
  if (!kind || kind === 'UNSPECIFIED') return null;
  for (const options of Object.values(ARTIFACT_KINDS_BY_DOC_TYPE)) {
    const match = options.find((o) => o.value === kind);
    if (match) return match.label;
  }
  return formatArtifactKind(kind);
}

export function formatArtifactKind(kind) {
  if (!kind || kind === 'UNSPECIFIED') return null;
  return String(kind).replaceAll('_', ' ');
}

/** Short label for matrices/chips — prefers document code (UC-01). */
export function componentLabel(component) {
  if (!component) return '';
  const code = (component.codeName || '').trim();
  if (code) return code;
  const name = (component.name || '').trim();
  if (!name) return 'Component';
  const match = name.match(
    /\b((?:UC|TC|FR|NFR|CL|CD|SQ|AD|DFD|CTX|MS|TK|DL|TD|TL|WF|UI|ER)[-\s_]?\d{1,3})\b/i,
  );
  if (match) {
    return match[1].toUpperCase().replace(/[\s_]+/g, '-').replace(/([A-Z]+)(\d)/, '$1-$2');
  }
  return name.length > 28 ? `${name.slice(0, 27)}…` : name;
}

export function preferredArtifactHint(goalKind) {
  if (goalKind === 'GENERAL') {
    return 'Prefer module-level artifacts (context/data modules, classes, milestones, deliverables).';
  }
  return 'Prefer function/transaction artifacts (use cases, activities, sequences, UI, test cases).';
}

/** Order goals as GENERAL parents, then SPECIFIC children, then orphan specifics. */
export function orderGoalsHierarchically(goals) {
  if (!Array.isArray(goals) || goals.length === 0) return [];
  const byId = new Map(goals.map((g) => [g.id, g]));
  const childrenByParent = new Map();
  const roots = [];
  const orphans = [];

  goals.forEach((g) => {
    if (g.goalKind === 'GENERAL') {
      roots.push(g);
      return;
    }
    if (g.parentGoalId && byId.has(g.parentGoalId)) {
      if (!childrenByParent.has(g.parentGoalId)) childrenByParent.set(g.parentGoalId, []);
      childrenByParent.get(g.parentGoalId).push(g);
      return;
    }
    orphans.push(g);
  });

  const ordered = [];
  roots.forEach((parent) => {
    ordered.push(parent);
    (childrenByParent.get(parent.id) || []).forEach((child) => ordered.push(child));
  });
  orphans.forEach((g) => ordered.push(g));

  const seen = new Set(ordered.map((g) => g.id));
  goals.forEach((g) => {
    if (!seen.has(g.id)) ordered.push(g);
  });
  return ordered;
}

/**
 * Group a flat goals list into SMART Goal clusters: a GENERAL objective together
 * with its SPECIFIC children counts as ONE SMART Goal. An orphan SPECIFIC goal
 * (no GENERAL parent) is its own single-member cluster.
 * Each cluster: { id, primary, children }, where `id` (== primary.id) is the
 * canonical goal id used for selection and component mapping.
 */
export function groupGoalsIntoClusters(goals) {
  if (!Array.isArray(goals) || goals.length === 0) return [];
  const clusterById = new Map();
  const order = [];

  goals.forEach((g) => {
    if (g.goalKind === 'GENERAL') {
      clusterById.set(g.id, { id: g.id, primary: g, children: [] });
    }
  });

  goals.forEach((g) => {
    if (g.goalKind === 'GENERAL') {
      if (!order.includes(g.id)) order.push(g.id);
      return;
    }
    const parentCluster = g.parentGoalId ? clusterById.get(g.parentGoalId) : null;
    if (parentCluster) {
      parentCluster.children.push(g);
      if (!order.includes(parentCluster.id)) order.push(parentCluster.id);
    } else {
      clusterById.set(g.id, { id: g.id, primary: g, children: [] });
      order.push(g.id);
    }
  });

  return order.map((id) => clusterById.get(id));
}

/** Combined categoryStatus for a cluster: a doc type is covered if ANY objective
 *  in the cluster (general or any of its specifics) has it mapped. */
export function clusterCategoryStatus(cluster, docTypes) {
  const members = [cluster.primary, ...cluster.children];
  const status = {};
  docTypes.forEach((dt) => {
    status[dt] = members.some((g) => g.categoryStatus?.[dt]);
  });
  return status;
}

