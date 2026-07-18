import { API_BASE_URL } from '../api';

const SYNCTRACE_BASE_URL = `${API_BASE_URL}/synctrace`;

async function request(path, options = {}) {
  const response = await fetch(`${SYNCTRACE_BASE_URL}${path}`, options);
  const contentType = response.headers.get('content-type') || '';

  const payload = contentType.includes('application/json')
    ? await response.json()
    : await response.text();

  if (!response.ok) {
    const errorMessage = typeof payload === 'string'
      ? payload
      : payload?.error || 'SyncTrace request failed.';
    throw new Error(errorMessage);
  }

  return payload;
}

export const getSmartGoals = async () => request('/goals');

export const createSmartGoal = async (description) => request('/goals', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ description }),
});

export const deleteSmartGoal = async (goalId) => request(`/goals/${goalId}`, {
  method: 'DELETE',
});

export const getTraceComponents = async (docType, search) => {
  const params = new URLSearchParams();
  if (docType) params.set('docType', docType);
  if (search) params.set('search', search);
  const query = params.toString();
  return request(`/components${query ? `?${query}` : ''}`);
};

export const createTraceComponent = async (docType, name, content) => request('/components', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ docType, name, content }),
});

export const renameTraceComponent = async (componentId, name) => request(`/components/${componentId}/rename`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ name }),
});

export const deleteTraceComponent = async (componentId) => request(`/components/${componentId}`, {
  method: 'DELETE',
});

export const extractTraceComponents = async (historyId) => request('/components/extract', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ historyId }),
});

export const getGoalComponents = async (goalId) => request(`/goals/${goalId}/components`);

export const getAllGoalComponents = async () => request('/goal-components');

export const getTraceComponent = async (componentId) => request(`/components/${componentId}`);

export const addGoalComponents = async (goalId, componentIds) => request(`/goals/${goalId}/components`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ componentIds }),
});

export const removeGoalComponent = async (goalId, componentId) => request(`/goals/${goalId}/components/${componentId}`, {
  method: 'DELETE',
});
