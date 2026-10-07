export const studentTutorialSteps = [
  {
    target: null,
    center: true,
    content: 'Quick tour: you can tap anywhere on the screen to move to the next step.',
  },
  {
    target: '.student-sidebar',
    center: true,
    content: 'Welcome! This is your Student Workspace. Use this sidebar to access your info and sign out.',
    placement: 'right',
    disableBeacon: true,
  },
  {
    target: '.student-profile',
    content: 'Your profile and section details appear here for quick reference.',
    placement: 'right',
  },
  {
    target: '.student-detail',
    content: 'This is your Team Code. Share this with your professor to link your submissions.',
    placement: 'right',
  },
  {
    target: '.student-members',
    content: 'This is the list of your team members. Contact your professor if there are any changes needed.',
    placement: 'right',
  },
  {
    target: '.student-nav-btn--evaluations',
    content: 'Use this sidebar to switch between workspaces. Click "Evaluations" to see the feedback your professor sent.',
    placement: 'right',
    requireClick: true,
  },
  {
    target: '.student-header-search',
    content: 'Search for a specific evaluation by file name or keyword.',
    placement: 'bottom',
  },
  {
    target: '.student-doc-tabs .student-doc-tab',
    content: 'Filter evaluations by document type. Choose "All" to see everything.',
    placement: 'bottom',
  },
  {
    target: '.student-stats',
    content: 'See total evaluations and counts by document type at a glance.',
    placement: 'top',
  },
  {
    target: '.layout--student .app-table',
    content: 'Your evaluation reports appear here. Open any report to view feedback and annotations.',
    placement: 'top',
  },
  {
    target: '.student-nav-btn--traceability',
    content: 'Now click "Traceability" to open your team\'s traceability workspace.',
    placement: 'right',
    requireClick: true,
  },
  {
    target: '.student-trace-tabs',
    content: 'Use these tabs to map components, review traceability results, and register your GitHub source code.',
    placement: 'bottom',
  },

  // ── Traceability: Mapping ──────────────────────────────────────────────────
  {
    target: '.student-trace-tab--mapping',
    content: 'Let\'s start with mapping. Click "Mapping".',
    placement: 'bottom',
    requireClick: true,
  },
  {
    target: '.student-mapping__extract-goals',
    content: 'Step 1: Extract SMART Goals pulls the general and specific objectives from your evaluated project proposal. These goals are what every other document traces back to. (This button appears in the Proposal → SRS stage once your team has an evaluated document.)',
    placement: 'bottom',
  },
  {
    target: '.student-mapping__extract-components',
    content: 'Step 2: Extract Components pulls the individual components (use cases, class diagrams, test cases, and so on) out of your evaluated SRS, SDD, STD, and SPMP so you can map them. Only the documents for the selected stage are listed, e.g. SRS → SDD shows just your SRS and SDD.',
    placement: 'bottom',
  },
  {
    target: '.student-stm-context-row',
    content: 'Mapping happens in stages, one pair of documents at a time: Proposal → SRS, SRS → SDD, SRS → STD, SRS → SPMP, and SDD → Implementation. Pick a stage here. The badge shows whether that stage is Not Started, In Progress, Mapped, or Needs Attention.',
    placement: 'bottom',
  },
  {
    target: '.student-trace-panel--mapping .stm-workspace > .stm-column-wrap:first-child',
    content: 'The left column holds the source items for the selected stage: SMART goals for Proposal → SRS, or document components for later stages. Click one to select it. Use the search box to filter and Preview to read a component\'s full content.',
    placement: 'right',
  },
  {
    target: '.student-trace-panel--mapping .stm-workspace > .stm-column-wrap:last-child',
    content: 'The right column shows the target components. Select one or more targets that the selected source is connected to. The number on each card shows how many links it already has.',
    placement: 'left',
  },
  {
    target: '.student-trace-panel--mapping .stm-controls',
    content: 'The middle panel previews the source to target link. Click "Establish Mapping" to create it. Every mapping for this stage is listed below, where you can also remove one you made by mistake.',
    placement: 'left',
  },
  {
    target: '.student-mapping__save',
    content: 'Mappings are not stored until you click Save Mapping. Saving also verifies the stage, and a banner tells you whether it passed or needs attention. Save before switching stages or leaving the page.',
    placement: 'bottom',
  },

  // ── Traceability: Results ──────────────────────────────────────────────────
  {
    target: '.student-trace-tab--results',
    content: 'Next, see how well everything connects. Click "Results".',
    placement: 'bottom',
    requireClick: true,
  },
  {
    target: '.student-traceability-kpis',
    content: 'These cards show your coverage summary: overall alignment, goals with no matching components, goals with only some components, the number of missing links, and goals fully traced across all documents.',
    placement: 'bottom',
  },
  {
    target: '.student-trace-panel--results .tr-results',
    content: 'The traceability matrix shows each SMART goal as a row and each document type as a column. Filled cells list the components that trace to that goal. Empty cells are gaps to fix. Click a component to preview it, or use the search to find a goal.',
    placement: 'top',
  },
  {
    target: '.student-results__ai',
    content: 'Run AI Analysis checks your mappings for continuity issues, such as goals that stop at the SRS or designs with no tests, and suggests how to fix them. It can take a minute; a progress bar shows its status.',
    placement: 'bottom',
  },
  {
    target: '.student-trace-panel--results .iap-grid',
    content: 'Issues found by the analysis are listed here, sorted by severity. Select an issue to read its details and recommendation. Fix the gap in Mapping, save, then Refresh and re-run the analysis to confirm.',
    placement: 'top',
  },
  {
    target: '.student-results__refresh',
    content: 'Click Refresh after saving new mappings to update the KPIs and matrix.',
    placement: 'bottom',
  },

  // ── Traceability: Source Code ──────────────────────────────────────────────
  {
    target: '.student-trace-tab--source',
    content: 'Finally, connect your code. Click "Source Code".',
    placement: 'bottom',
    requireClick: true,
  },
  {
    target: '.sc-root--embedded .sc-section--ingest',
    content: 'Paste your team\'s GitHub repository URL (it is pre-filled if your team submitted one) and click Ingest Repository. Each source file becomes an implementation component. Click a file in the list to view its code.',
    placement: 'bottom',
  },
  {
    target: '.sc-root--embedded .sc-section--alignment',
    content: 'After ingesting, Run Alignment Analysis compares your code against your SDD components and lists mismatches by severity, such as designed classes with no implementation.',
    placement: 'top',
  },
  {
    target: '.student-trace-tab--mapping',
    content: 'Tip: once your code is registered, return to Mapping and choose the SDD → Implementation stage to link design components to the files that implement them. That completes your traceability chain.',
    placement: 'bottom',
  },
  {
    target: '.student-sidebar__signout',
    content: 'You can sign out here when you are done.',
    placement: 'top',
  },
];
