const REPOSITORY_ROLES = ['FRONTEND', 'BACKEND', 'MOBILE', 'SHARED', 'OTHER'];

const STATUS_LABELS = {
  NOT_INGESTED: 'Not ingested',
  INGESTING: 'Ingesting',
  INGESTED: 'Ingested',
  FAILED: 'Failed',
};

function RepositoryCard({ repository, busy = false, onIngest, onRoleChange, onUnlink }) {
  const status = repository.ingestionStatus || 'NOT_INGESTED';
  return (
    <article className="sc-repository-card">
      <div className="sc-repository-card__main">
        <a href={repository.repositoryUrl} target="_blank" rel="noreferrer" className="sc-repository-card__name">
          {repository.owner}/{repository.repo}
        </a>
        <span className={`sc-repository-status sc-repository-status--${status.toLowerCase()}`}>
          {STATUS_LABELS[status] || status}
        </span>
      </div>
      <div className="sc-repository-card__meta">
        <label>
          Role
          <select
            value={repository.role || 'OTHER'}
            onChange={(event) => onRoleChange(repository, event.target.value)}
            disabled={busy}
            aria-label={`Role for ${repository.owner}/${repository.repo}`}
          >
            {REPOSITORY_ROLES.map((role) => (
              <option key={role} value={role}>{role[0] + role.slice(1).toLowerCase()}</option>
            ))}
          </select>
        </label>
        <span>{repository.ingestedFileCount || 0} files</span>
        <span>
          {repository.lastIngestedAt
            ? `Last ingested ${new Date(repository.lastIngestedAt).toLocaleString()}`
            : 'Never ingested'}
        </span>
      </div>
      {repository.lastIngestError && (
        <p className="sc-repository-card__error" role="alert">{repository.lastIngestError}</p>
      )}
      <div className="sc-repository-card__actions">
        <button
          type="button"
          className="btn btn--primary"
          onClick={() => onIngest(repository)}
          disabled={busy || status === 'INGESTING'}
        >
          {status === 'FAILED' ? 'Retry' : status === 'INGESTED' ? 'Re-ingest' : 'Ingest'}
        </button>
        <button
          type="button"
          className="btn btn--soft"
          onClick={() => onUnlink(repository)}
          disabled={busy}
        >
          Unlink
        </button>
      </div>
    </article>
  );
}

export default RepositoryCard;
