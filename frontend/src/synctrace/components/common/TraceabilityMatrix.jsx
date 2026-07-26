import { useMemo, useState } from 'react';
import { CircleCheck, CircleX, Link2, Search } from 'lucide-react';
import { componentLabel } from '../../constants';
import './TraceabilityMatrix.css';

const DOC_COLUMNS = [
  { key: 'SRS', label: 'SRS' },
  { key: 'SDD', label: 'SDD' },
  { key: 'SPMP', label: 'SPMP' },
  { key: 'STD', label: 'STD' },
  { key: 'IMPLEMENTATION', label: 'Implementation' },
];

function TraceabilityMatrix({ rows, onComponentClick, onAddClick, emptyMessage = 'No SMART goals to display.' }) {
  const [search, setSearch] = useState('');

  const filteredRows = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((row) => {
      const haystack = [
        row.code,
        row.description,
        ...DOC_COLUMNS.flatMap((col) => (row.cells[col.key] || []).map((c) => componentLabel(c))),
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

  return (
    <div className="trm-wrap">
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

      <div className="trm-table-wrap">
        <div className="trm-table">
          <div className="trm-th trm-th--goal">
            <span>SMART Goal</span>
          </div>
          {DOC_COLUMNS.map((col) => (
            <div className="trm-th" key={col.key}>
              <span>{col.label}</span>
            </div>
          ))}
          <div className="trm-th trm-th--last">
            <span>Status</span>
          </div>

          {filteredRows.length === 0 && (
            <div className="trm-empty-filter">No goals match “{search.trim()}”.</div>
          )}

          {filteredRows.map((row) => {
            const complete = DOC_COLUMNS.every((col) => (row.cells[col.key] || []).length > 0);
            return (
              <div className="trm-row" key={row.goalId}>
                <div className="trm-cell trm-cell--goal">
                  <span className="trm-goal-code">{row.code}</span>
                  <span className="trm-goal-desc">{row.description}</span>
                </div>

                {DOC_COLUMNS.map((col) => {
                  const components = row.cells[col.key] || [];
                  return (
                    <div className="trm-cell" key={col.key}>
                      {components.length === 0 ? (
                        <span
                          className="trm-chip trm-chip--missing"
                          onClick={() => onAddClick?.(row, col.key)}
                        >
                          Missing
                        </span>
                      ) : (
                        <div className="trm-chip-stack">
                          {components.map((c) => {
                            const label = componentLabel(c);
                            return (
                              <button
                                type="button"
                                key={c.id}
                                className={`trm-chip ${col.key === 'IMPLEMENTATION' ? 'trm-chip--impl' : 'trm-chip--doc'}`}
                                onClick={() => onComponentClick?.(c)}
                                title={c.name || label}
                              >
                                {col.key === 'IMPLEMENTATION' && <Link2 size={12} />}
                                <span className="trm-chip__label">{label}</span>
                              </button>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  );
                })}

                <div className="trm-cell trm-cell--last trm-cell--status">
                  {complete ? (
                    <span className="trm-status-badge trm-status-badge--pass">
                      <CircleCheck size={14} /> Passed
                    </span>
                  ) : (
                    <span className="trm-status-badge trm-status-badge--fail">
                      <CircleX size={14} /> Failed
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export default TraceabilityMatrix;
