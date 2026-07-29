/** Shared SyncTrace taxonomy matching adviser requirements. */

export const DOC_TYPES = ['SRS', 'SDD', 'SPMP', 'STD', 'IMPLEMENTATION'];

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

