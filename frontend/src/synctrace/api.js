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

// Mapped components for every goal in one call — avoids an N+1 fetch loop per goal.
export const getAllGoalComponents = async () => {
    const response = await fetch(`${API_BASE_URL}/synctrace/goals/components`);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Failed to fetch goal mappings.');
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
