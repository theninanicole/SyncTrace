import AppModal from '../../../components/common/AppModal';

function ConfirmModal({
  isOpen,
  title = 'Are you sure?',
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  danger = false,
  submitting = false,
  onConfirm,
  onCancel,
}) {
  return (
    <AppModal
      isOpen={isOpen}
      onClose={onCancel}
      title={title}
      containerClassName="tm-confirm-modal"
      footer={
        <div className="modal-actions" style={{ justifyContent: 'flex-end', width: '100%' }}>
          <button className="btn" onClick={onCancel} disabled={submitting}>{cancelLabel}</button>
          <button
            className={`btn ${danger ? 'btn--danger' : 'btn--primary'}`}
            onClick={onConfirm}
            disabled={submitting}
          >
            {submitting ? 'Working...' : confirmLabel}
          </button>
        </div>
      }
    >
      <p className="tm-muted">{message}</p>
    </AppModal>
  );
}

export default ConfirmModal;
