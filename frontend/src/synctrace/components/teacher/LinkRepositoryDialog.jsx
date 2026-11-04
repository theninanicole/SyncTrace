import { useState } from 'react';

const REPOSITORY_ROLES = ['FRONTEND', 'BACKEND', 'MOBILE', 'SHARED', 'OTHER'];

function LinkRepositoryDialog({ teamCode, initialUrl = '', saving = false, onClose, onSubmit }) {
  const [githubUrl, setGithubUrl] = useState(initialUrl);
  const [role, setRole] = useState('OTHER');

  function handleSubmit(event) {
    event.preventDefault();
    onSubmit({ githubUrl: githubUrl.trim(), role });
  }

  return (
    <div className="sc-dialog-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !saving) onClose();
    }}>
      <section
        className="sc-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sc-link-dialog-title"
      >
        <h3 id="sc-link-dialog-title">Link GitHub repository</h3>
        <p>Link a repository to team {teamCode}.</p>
        <form onSubmit={handleSubmit}>
          <label className="sc-dialog__label" htmlFor="sc-repository-url">Repository URL</label>
          <input
            id="sc-repository-url"
            className="sc-input"
            type="url"
            value={githubUrl}
            onChange={(event) => setGithubUrl(event.target.value)}
            placeholder="https://github.com/owner/repo"
            required
            disabled={saving}
          />
          <label className="sc-dialog__label" htmlFor="sc-repository-role">Repository role</label>
          <select
            id="sc-repository-role"
            className="sc-input"
            value={role}
            onChange={(event) => setRole(event.target.value)}
            disabled={saving}
          >
            {REPOSITORY_ROLES.map((item) => (
              <option key={item} value={item}>{item[0] + item.slice(1).toLowerCase()}</option>
            ))}
          </select>
          <div className="sc-dialog__actions">
            <button type="button" className="btn btn--soft" onClick={onClose} disabled={saving}>Cancel</button>
            <button type="submit" className="btn btn--primary" disabled={saving || !githubUrl.trim()}>
              {saving ? 'Checking...' : 'Link repository'}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}

export default LinkRepositoryDialog;
