function SendButton({ showToast }) {
  return (
    <button type="button" className="btn btn--primary" onClick={() => showToast('Send coming soon', 'info')}>
      Send
    </button>
  );
}

export default SendButton;
