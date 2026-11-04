function UnlinkConfirmModal({ repository, impact, loading = false, onCancel, onConfirm }) {
  if (!repository) return null;
  const names = impact?.componentNames || [];

  return (
    <div className="sc-dialog-backdrop" role="presentation">
      <section
        className="sc-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sc-unlink-dialog-title"
      >
        <h3 id="sc-unlink-dialog-title">Unlink {repository.owner}/{repository.repo}?</h3>
        {loading ? (
          <p>Checking linked components and mappings...</p>
        ) : (
          <>
            <p>
              {names.length
                ? `${names.length} implementation component(s) and ${impact?.mappingCount || 0} mapping(s) will be marked as Source removed. They will not be deleted.`
                : 'No ingested components or mappings will be affected.'}
            </p>
            {names.length > 0 && (
              <ul className="sc-dialog__list">
                {names.slice(0, 10).map((name) => <li key={name}>{name}</li>)}
                {names.length > 10 && <li>And {names.length - 10} more component(s)</li>}
              </ul>
            )}
          </>
        )}
        <div className="sc-dialog__actions">
          <button type="button" className="btn btn--soft" onClick={onCancel} disabled={loading}>Cancel</button>
          <button type="button" className="btn btn--primary" onClick={onConfirm} disabled={loading}>
            Unlink repository
          </button>
        </div>
      </section>
    </div>
  );
}

export default UnlinkConfirmModal;
