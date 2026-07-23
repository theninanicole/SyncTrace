import { extractSubmissionMeta, getDisplayType, formatDateTime } from '../../utils/dashboardUtils';

function TeacherSubmissionsTable({ files, loading, isSyncing, analyzedFileIds, onSort, onAnalyze, onViewHistory }) {
  return (
    <div className="card">
      <table className="app-table" id="teacher-submission-table">
        <thead>
          <tr>
            <th>Submission Identity</th>
            <th className="teacher-submissions__type-col" onClick={() => onSort('mimeType')}>Submission Type</th>
            <th onClick={() => onSort('date')}>Date Submitted</th>
            <th className="teacher-submissions__actions-col">Actions</th>
          </tr>
        </thead>
        <tbody>
          {loading || isSyncing ? (
            <tr>
              <td colSpan="4" className="muted">Loading submissions...</td>
            </tr>
          ) : files.length === 0 ? (
            <tr>
              <td colSpan="4" className="muted">No submissions match the selected filters.</td>
            </tr>
          ) : (
            files.map((file, index) => {
              const docType = extractSubmissionMeta(file.name).documentType;
              const isGithub = docType === 'GITHUB' || file.mimeType === 'text/x-github-url';

              return (
                <tr key={`${file.id}-${index}`}>
                  <td>
                    <a href={file.webViewLink} target="_blank" rel="noreferrer" className="strong link-reset">
                      {file.name}
                    </a>
                  </td>
                  <td className="teacher-submissions__type-col">{getDisplayType(file)}</td>
                  <td>{formatDateTime(file.submittedAt)}</td>
                  <td className="teacher-submissions__actions-col">
                    <div className="teacher-submissions__actions">
                      {isGithub ? (
                        <a
                          className="btn btn--soft teacher-submissions__action-btn"
                          href={file.webViewLink}
                          target="_blank"
                          rel="noreferrer"
                        >
                          Open GitHub
                        </a>
                      ) : (
                        <button className="btn btn--soft teacher-submissions__action-btn" onClick={() => onAnalyze(file)}>
                          {analyzedFileIds?.has(file.id) ? 'Re-Evaluate' : 'Run AI Analysis'}
                        </button>
                      )}
                      {!isGithub && (
                        <button className="btn btn--soft teacher-submissions__history-btn" onClick={() => onViewHistory?.(file)}>
                          View History
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}

export default TeacherSubmissionsTable;
