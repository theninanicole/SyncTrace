/**
 * The "SMART Goals" section of a proposal evaluation report.
 *
 * The report is plain text, so the section is plain text too:
 *
 *   SMART Goals:
 *   * G1: General objective
 *     - G1.1: Specific objective
 *   * G2: General objective
 *
 * Teachers edit it as part of the AI evaluation text. It is read here for the report view
 * and for SyncTrace's Extract SMART Goals (which takes its goals from this section).
 * Goal tree shape: [{ goalKind: 'GENERAL', description, children: [{ goalKind: 'SPECIFIC', description }] }]
 */

export const SMART_GOALS_HEADING = 'SMART Goals';

/** Every heading the evaluation report is split on. */
export const REPORT_SECTION_HEADINGS = [
  'Summary', 'Rubric Evaluation', 'Strengths', 'Weaknesses', 'Missing Sections', 'Recommendations',
  'Conclusion', 'Revision Analysis', 'Remaining Issues', 'Next Steps', 'Diagram Analysis',
  SMART_GOALS_HEADING,
];

// A heading on a line of its own: "SMART Goals:", "**SMART Goals:**", "## SMART Goals".
const headingLineRe = (names) =>
  new RegExp(`^[ \\t]*(?:#{1,3}[ \\t]*)?(?:\\*\\*)?(?:${names}):?(?:\\*\\*)?:?[ \\t]*\\r?$`, 'm');

const DIAGRAM_ANALYSIS_HEADING = 'Diagram Analysis';

const BULLET_RE = /^(?:[-*•]|\d+[.)])\s+/;
const LABEL_RE = /^G\s*\d+(\.\d+)?\s*[:.)–—-]\s*/i;

function locateSection(text, name = SMART_GOALS_HEADING) {
  const heading = text.match(headingLineRe(name));
  if (!heading) return null;
  const bodyStart = heading.index + heading[0].length;
  const others = REPORT_SECTION_HEADINGS.filter((other) => other !== name).join('|');
  const next = text.slice(bodyStart).match(headingLineRe(others));
  return { start: heading.index, bodyStart, end: next ? bodyStart + next.index : text.length };
}

const oneLine = (value) => String(value || '').replace(/\s+/g, ' ').trim();

/** Section body → goal tree. Tolerates hand edits: labels and bullets are optional. */
export function parseSmartGoalsBody(body) {
  const goals = [];
  let current = null;

  String(body || '').split('\n').forEach((raw) => {
    if (!raw.trim()) return;
    const indented = /^\s/.test(raw);
    const line = raw.trim().replace(BULLET_RE, '').replace(/\*\*/g, '');
    const label = line.match(LABEL_RE);
    const description = oneLine(label ? line.slice(label[0].length) : line);
    if (!description) return;
    // "None found." and the like — the report's way of saying there are no goals.
    if (!label && /^none\b/i.test(description)) return;

    const isChild = label ? Boolean(label[1]) : indented;
    if (isChild && current) {
      current.children.push({ goalKind: 'SPECIFIC', description });
      return;
    }
    current = { goalKind: 'GENERAL', description, children: [] };
    goals.push(current);
  });

  return goals;
}

/** found is false when the report has no SMART Goals section at all (e.g. an older report). */
export function readSmartGoals(reportText) {
  const text = String(reportText || '');
  const section = locateSection(text);
  if (!section) return { found: false, goals: [] };
  return { found: true, goals: parseSmartGoalsBody(text.slice(section.bodyStart, section.end)) };
}

/** Report sections in display order: SMART Goals always directly follows Diagram Analysis. */
export function orderReportSections(sections) {
  const goals = sections.filter((section) => section.heading === SMART_GOALS_HEADING);
  const diagramIndex = sections.findIndex((section) => section.heading === DIAGRAM_ANALYSIS_HEADING);
  if (goals.length === 0 || diagramIndex === -1) return sections;
  const rest = sections.filter((section) => section.heading !== SMART_GOALS_HEADING);
  const insertAt = rest.findIndex((section) => section.heading === DIAGRAM_ANALYSIS_HEADING) + 1;
  return [...rest.slice(0, insertAt), ...goals, ...rest.slice(insertAt)];
}

// ── Editable draft of a goal tree (rows with stable keys for the goal editor) ──

let draftKey = 0;
export const nextGoalDraftKey = () => `draft-${++draftKey}`;

/** Goal tree → editable rows with stable keys. */
export function toEditableGoalDraft(goals) {
  return (goals || []).map((goal) => ({
    key: nextGoalDraftKey(),
    goalKind: goal.goalKind === 'GENERAL' ? 'GENERAL' : 'SPECIFIC',
    description: goal.description || '',
    children: (goal.children || []).map((child) => ({
      key: nextGoalDraftKey(),
      description: child.description || '',
    })),
  }));
}

/** Editable rows → goal tree, dropping anything left blank. */
export function toGoalPayload(draft) {
  return (draft || [])
    .filter((goal) => oneLine(goal.description))
    .map((goal) => ({
      goalKind: goal.goalKind,
      description: oneLine(goal.description),
      children: goal.children
        .filter((child) => oneLine(child.description))
        .map((child) => ({ goalKind: 'SPECIFIC', description: oneLine(child.description) })),
    }));
}
