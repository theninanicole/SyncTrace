import { API_BASE_URL } from '../api';

// ── SyncTrace: Traceability Mapping ─────────────────────────────────────────

export const getSmartGoals = async () => {
    const response = await fetch(`${API_BASE_URL}/synctrace/goals`);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to fetch SMART goals.');
    return data;
};

export const createSmartGoal = async (description) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/goals`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ description }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to create goal.');
    return data;
};

export const deleteSmartGoal = async (goalId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/goals/${goalId}`, {
        method: 'DELETE',
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to delete goal.');
    return data;
};

export const getTraceComponents = async (docType, search) => {
    const params = new URLSearchParams();
    if (docType) params.set('docType', docType);
    if (search) params.set('search', search);
    const query = params.toString();
    const response = await fetch(`${API_BASE_URL}/synctrace/components${query ? `?${query}` : ''}`);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to fetch components.');
    return data;
};

export const createTraceComponent = async (docType, name, content) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/components`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ docType, name, content }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to create component.');
    return data;
};

export const renameTraceComponent = async (componentId, name) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/components/${componentId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to rename component.');
    return data;
};

export const deleteTraceComponent = async (componentId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/components/${componentId}`, {
        method: 'DELETE',
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to delete component.');
    return data;
};

export const extractTraceComponents = async (historyId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/components/extract`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ historyId }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Extraction failed.');
    return data;
};

export const getGoalComponents = async (goalId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/goals/${goalId}/components`);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to fetch goal mappings.');
    return data;
};

export const getAllGoalComponents = async () => {
    const response = await fetch(`${API_BASE_URL}/synctrace/goals/components`);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to fetch all goal components.');
    return data;
};

export const getTraceComponent = async (componentId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/components/${componentId}`);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to fetch component.');
    return data;
};

export const addGoalComponents = async (goalId, componentIds) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/goals/${goalId}/components`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ componentIds }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to add components.');
    return data;
};

export const removeGoalComponent = async (goalId, componentId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/goals/${goalId}/components/${componentId}`, {
        method: 'DELETE',
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to remove component.');
    return data;
};

// ── SyncTrace: Proposal Analysis ─────────────────────────────────────────────

export const extractSmartGoalsFromProposal = async (fileId, fileName, model, sessionId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/proposals/extract-goals`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fileId, fileName, model, sessionId }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to extract SMART goals.');
    return data;
};

// ── SyncTrace: Continuity Analysis ────────────────────────────────────────────

export const analyzeSourceCodeAlignment = async (teamCode, model, sessionId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/continuity/align`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ teamCode, model, sessionId }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to analyze source code alignment.');
    return data;
};

export const detectContinuityGaps = async (teamCode, goalId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/continuity/detect-gaps`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ teamCode, goalId }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to detect continuity gaps.');
    return data;
};

export const generateDiagnosticRecommendations = async (teamCode, model, sessionId) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/continuity/recommendations`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ teamCode, model, sessionId }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to generate recommendations.');
    return data;
};

// ── SyncTrace: GitHub Ingestion ───────────────────────────────────────────────

export const getTeamRepositories = async () => {
    const response = await fetch(`${API_BASE_URL}/synctrace/github/teams`);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to fetch team repositories.');
    return data;
};

export const ingestRepository = async ({ teamCode, githubUrl, sessionId }) => {
    const response = await fetch(`${API_BASE_URL}/synctrace/github/ingest`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ teamCode, githubUrl, sessionId }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to ingest repository.');
    return data;
};

// ── SyncTrace: Audit Export ─────────────────────────────────────────────────

export const exportAuditReport = async (teamCode, format = 'json') => {
    const response = await fetch(`${API_BASE_URL}/synctrace/audit/${teamCode}/export?format=${format}`);
    if (!response.ok) {
        const data = await response.json();
        throw new Error(data.error || 'Failed to export audit report.');
    }
    
    // Handle binary response for PDF/CSV
    if (format === 'pdf' || format === 'csv') {
        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `audit-report-${teamCode}.${format}`;
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
        document.body.removeChild(a);
        return { success: true };
    }
    
    // JSON response
    return response.json();
};
