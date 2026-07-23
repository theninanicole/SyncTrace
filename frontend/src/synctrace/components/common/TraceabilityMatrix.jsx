import { useMemo, useState } from 'react';
import { Plus, Search } from 'lucide-react';
import { componentLabel, DOC_TYPES } from '../../constants';
import './TraceabilityMatrix.css';

const DOC_COLUMNS = [
  { key: 'SRS', label: 'SRS' },
  { key: 'SDD', label: 'SDD' },
  { key: 'SPMP', label: 'SPMP' },
  { key: 'STD', label: 'STD' },
  { key: 'IMPLEMENTATION', label: 'CODE' },
];

function rowStatus(row) {
  const covered = DOC_COLUMNS.filter((col) => (row.cells[col.key] || []).length > 0).length;
  if (covered === 0) return 'unmapped';
  if (covered < DOC_COLUMNS.length) return 'gap';
  return 'linked';
}

function statusLabel(status) {
  if (status === 'linked') return 'Linked';
  if (status === 'gap') return 'Gap';
  return 'Unmapped';
}

function formatGoalCode(code) {
  if (!code) return 'G-00';
  const raw = String(code).replace(/^G-?/i, '');
  const num = Number.parseInt(raw, 10);
  if (Number.isFinite(num)) return `G-${String(num).padStart(2, '0')}`;
  return String(code);
}

function TraceabilityMatrix({
  rows,
  onComponentClick,
  onAddClick,
  emptyMessage = 'No SMART goals to display.',
  title = 'SMART goal traceability matrix',
}) {
  const [search, setSearch] = useState('');

  const filteredRows = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((row) => {
      const haystack = [
        row.code,
        row.description,
        row.teamCode,
        row.diagnosis,
        ...(DOC_TYPES.flatMap((dt) => (row.cells[dt] || []).map((c) => `${c.codeName || ''} ${c.name || ''}`))),
      ].join(' ').toLowerCase();
      return haystack.includes(q);
    });
  }, [rows, search]);

  if (rows.length === 0) {
    return (
      <div className="empty-state">
        <p>{emptyMessage}</p>
      </div>
    );
  }

  const colCount = DOC_COLUMNS.length + 2;

  return (
    <section className="trm-card">
      <div className="trm-card__header">
        <h2 className="trm-card__title">{title}</h2>
        <label className="trm-search">
          <Search size={15} aria-hidden="true" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search goals"
            aria-label="Search goals"
          />
        </label>
      </div>

      <div className="trm-table-wrap">
        <table className="trm-table" aria-label={title}>
          <thead>
            <tr>
              <th scope="col" className="trm-th trm-th--goal">SMART GOAL</th>
              {DOC_COLUMNS.map((col) => (
                <th scope="col" className="trm-th" key={col.key}>{col.label}</th>
              ))}
              <th scope="col" className="trm-th trm-th--status">STATUS</th>
            </tr>
          </thead>
          <tbody>
            {filteredRows.length === 0 ? (
              <tr>
                <td className="trm-empty-filter" colSpan={colCount}>
                  No goals match “{search.trim()}”.
                </td>
              </tr>
            ) : filteredRows.map((row) => {
              const status = rowStatus(row);
              return (
                <tr
                  className={`trm-row ${row.nested ? 'trm-row--nested' : ''}`}
                  key={row.goalId}
                >
                  <th scope="row" className="trm-cell trm-cell--goal">
                    <span className="trm-goal-id">{formatGoalCode(row.code)}</span>
                    <span className="trm-goal-desc" title={row.description}>{row.description}</span>
                    {row.diagnosis && (
                      <p className="trm-diagnosis">
                        <span className="trm-diagnosis__label">AI diagnosis:</span> {row.diagnosis}
                      </p>
                    )}
                  </th>

                  {DOC_COLUMNS.map((col) => {
                    const components = row.cells[col.key] || [];
                    return (
                      <td className="trm-cell" key={col.key}>
                        {components.length === 0 ? (
                          <button
                            type="button"
                            className="trm-add"
                            onClick={() => onAddClick?.(row, col.key)}
                            title={`Add ${col.label} mapping`}
                          >
                            <Plus size={12} />
                            Add
                          </button>
                        ) : (
                          <div className="trm-chip-stack">
                            {components.map((c) => {
                              const label = componentLabel(c);
                              return (
                                <button
                                  type="button"
                                  key={c.id}
                                  className="trm-chip"
                                  onClick={() => onComponentClick?.(c)}
                                  title={c.name || label}
                                >
                                  <span className="trm-chip__label">{label}</span>
                                </button>
                              );
                            })}
                          </div>
                        )}
                      </td>
                    );
                  })}

                  <td className="trm-cell trm-cell--status">
                    <span className={`trm-status trm-status--${status}`}>
                      {statusLabel(status)}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export default TraceabilityMatrix;
