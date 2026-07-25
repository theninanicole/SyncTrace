import { useCallback, useState } from 'react';

const STORAGE_KEY = 'synctrace.selectedTeamCode';

export function useSelectedTeam() {
  const [selectedTeam, setSelectedTeamState] = useState(() => {
    try {
      return localStorage.getItem(STORAGE_KEY) || '';
    } catch {
      return '';
    }
  });

  const setSelectedTeam = useCallback((code) => {
    setSelectedTeamState(code);
    try {
      if (code) localStorage.setItem(STORAGE_KEY, code);
      else localStorage.removeItem(STORAGE_KEY);
    } catch { /* localStorage unavailable, keep in-memory only */ }
  }, []);

  return [selectedTeam, setSelectedTeam];
}
