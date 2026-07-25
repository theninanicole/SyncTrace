import { useEffect, useState } from 'react';
import { Users } from 'lucide-react';
import { fetchClassRoster } from '../../../services/dashboardService';
import { getTeamRepositories } from '../../api';
import './TeamSelect.css';

function TeamSelect({ value, onChange }) {
  const [teams, setTeams] = useState([]);

  useEffect(() => {
    // Team codes live in two independent sheets — the class roster and the
    // GitHub/responses sheet (Source Code page) — so a team added to only one
    // of them still needs to show up here. Merge both rather than trusting
    // either alone.
    Promise.all([
      fetchClassRoster().catch(() => []),
      getTeamRepositories().catch(() => []),
    ]).then(([roster, repositories]) => {
      const codes = new Set([
        ...roster.map((r) => r.groupCode),
        ...repositories.map((r) => r.teamCode),
      ].filter(Boolean));
      setTeams(Array.from(codes).sort());
    });
  }, []);

  return (
    <label className="team-select">
      <Users size={14} aria-hidden="true" />
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label="Filter by team"
      >
        <option value="" disabled hidden>Select a team</option>
        {teams.map((code) => (
          <option key={code} value={code}>{code}</option>
        ))}
      </select>
    </label>
  );
}

export default TeamSelect;
