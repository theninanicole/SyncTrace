export function formatDate(value) {
  return new Date(value).toLocaleDateString();
}

export function formatDateTime(value) {
  return new Date(value).toLocaleString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  });
}

function inferMimeType(value) {
  if (!value) return '';

  if (typeof value === 'string') {
    return value;
  }

  const mimeType = String(value.mimeType || '').trim();
  const webViewLink = String(value.webViewLink || '').toLowerCase();
  const name = String(value.name || '').toLowerCase();

  if (mimeType) return mimeType;
  if (webViewLink.includes('/folders/')) return 'application/vnd.google-apps.folder';
  if (webViewLink.includes('/document/d/')) return 'application/vnd.google-apps.document';
  if (webViewLink.includes('.pdf') || name.endsWith('.pdf')) return 'application/pdf';
  if (webViewLink.includes('.docx') || name.endsWith('.docx')) {
    return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
  }

  return '';
}

export function getDisplayType(value) {
  const mimeType = inferMimeType(value);

  switch (mimeType) {
    case 'application/vnd.google-apps.document':
      return 'Google Doc';
    case 'application/pdf':
      return 'PDF';
    case 'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
      return 'DOCX';
    case 'text/plain':
      return 'Plain Text';
    case 'application/vnd.google-apps.folder':
      return 'FOLDER (Invalid)';
    case 'text/x-github-url':
      return 'GitHub Repo';
    default:
      return 'Document';
  }
}

export const DOC_TYPES = ['SRS', 'SDD', 'SPMP', 'STD', 'PROPOSAL', 'GITHUB'];

export function normalizeSection(section) {
  if (!section) return '';
  const upper = String(section).trim().toUpperCase();
  const goMatch = upper.match(/^GO(\d)$/);
  if (goMatch) return `G0${goMatch[1]}`;
  const shortMatch = upper.match(/^G(\d)$/);
  if (shortMatch) return `G0${shortMatch[1]}`;
  return upper;
}

/**
 * Comparison key for a section, so the same section written differently in the submission
 * form and the class list still matches: "G4", "g4", "G 4", "G04" and "GO4" all give "G4".
 * Only for comparing; sections are always displayed as listed.
 */
export function sectionKey(section) {
  if (!section) return '';
  return String(section)
    .trim()
    .toUpperCase()
    .replace(/\s+/g, '')
    .replace(/^G[O0]+(?=\d)/, 'G')
    .replace(/(\D)0+(?=\d)/g, '$1');
}

export function sameSection(a, b) {
  return sectionKey(a) === sectionKey(b);
}

// Submission names are built as "[LATE] [DOC] {section} - {team code} | {student}" from the
// submission form (SubmissionSyncService), so the section is the exact text the form holds.
const SUBMISSION_NAME = /^(?:\[LATE\]\s*)?\[(SRS|SDD|SPMP|STD|PROPOSAL|GITHUB)\]\s*(.*?)\s+-\s+(.*?)\s*\|\s*(.*)$/i;

export function extractSubmissionMeta(fileName) {
  const name = String(fileName || '');
  const upper = name.toUpperCase();

  const docMatch =
    upper.match(/^\[(SRS|SDD|SPMP|STD|PROPOSAL|GITHUB)\]/) ||
    upper.match(/\b(SRS|SDD|SPMP|STD|PROPOSAL|GITHUB)\b/);
  const teamMatch = upper.match(/\b\d{4}-SEM\d-IT\d+-\d{2}\b/);
  const studentPart = name.includes('|') ? name.split('|').pop().trim() : '';

  // Section exactly as listed in the submission form; older/irregular names fall back to
  // the previous "G<n>" pattern.
  const structured = name.match(SUBMISSION_NAME);
  const listedSection = structured?.[2]?.trim();
  const fallbackSection = upper.match(/\bG[O0]?\d+\b/)?.[0] || '';
  const section = listedSection && !/^no section$/i.test(listedSection) ? listedSection : fallbackSection;

  return {
    documentType: docMatch?.[1] || '',
    section,
    teamCode: teamMatch?.[0] || '',
    studentName: studentPart || '',
  };
}

/** Adds each value once, comparing with keyFn; the first spelling seen is the one shown. */
function addUnique(map, value, keyFn) {
  const trimmed = String(value || '').trim();
  const key = keyFn(trimmed);
  if (key && !map.has(key)) map.set(key, trimmed);
}

export function buildFilterOptions(files = []) {
  const students = new Set();
  const sections = new Map();
  const teamCodes = new Set();

  files.forEach((item) => {
    const meta = extractSubmissionMeta(item.name);
    if (meta.studentName) students.add(meta.studentName);
    addUnique(sections, meta.section, sectionKey);
    if (meta.teamCode) teamCodes.add(meta.teamCode);
  });

  return {
    students: [...students].sort((a, b) => a.localeCompare(b)),
    sections: [...sections.values()].sort(compareSections),
    teamCodes: [...teamCodes].sort((a, b) => a.localeCompare(b)),
    docTypes: DOC_TYPES,
  };
}

/** Natural order, so G2 comes before G10. */
export function compareSections(a, b) {
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' });
}

/** Section options from submissions plus the class list, each section once. */
export function mergeSectionOptions(...lists) {
  const sections = new Map();
  lists.flat().forEach((section) => addUnique(sections, section, sectionKey));
  return [...sections.values()].sort(compareSections);
}

export function filterSubmissions(files = [], filters = {}) {
  const {
    selectedStudent = '',
    selectedSection = '',
    selectedTeamCode = '',
    selectedDocType = '',
    searchQuery = '',
  } = filters;

  const normalizedQuery = String(searchQuery).trim().toLowerCase();

  return files.filter((item) => {
    const meta = extractSubmissionMeta(item.name);

    if (selectedStudent && meta.studentName !== selectedStudent) return false;
    if (selectedSection && !sameSection(meta.section, selectedSection)) return false;
    if (selectedTeamCode && meta.teamCode !== selectedTeamCode.toUpperCase()) return false;
    if (selectedDocType && meta.documentType !== selectedDocType.toUpperCase()) return false;

    if (normalizedQuery) {
      const searchable = [
        item.name,
        meta.studentName,
        meta.section,
        meta.teamCode,
        meta.documentType,
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();

      if (!searchable.includes(normalizedQuery)) return false;
    }

    return true;
  });
}

export function sortSubmissions(files, sortConfig) {
  const items = [...files];
  return items.sort((a, b) => {
    // 1. Sort by File Name
    if (sortConfig.key === 'name') {
      return sortConfig.direction === 'asc'
        ? a.name.localeCompare(b.name)
        : b.name.localeCompare(a.name);
    }
    
    // 2. Sort by Display Type (The Fix!)
    if (sortConfig.key === 'mimeType') {
      const typeA = getDisplayType(a);
      const typeB = getDisplayType(b);
      
      return sortConfig.direction === 'asc'
        ? typeA.localeCompare(typeB)
        : typeB.localeCompare(typeA);
    }
    
    // 3. Sort by Date
    if (sortConfig.key === 'date') {
      return sortConfig.direction === 'asc'
        ? new Date(a.submittedAt) - new Date(b.submittedAt)
        : new Date(b.submittedAt) - new Date(a.submittedAt);
    }
    
    return 0;
  });
}
