import { useState } from 'react';
import { publishTraceabilityResults } from '../../api';

function SendButton({ showToast, teamCode }) {
  const [sending, setSending] = useState(false);

  async function handleSend() {
    if (!teamCode) {
      showToast?.('Select a team before sending traceability results.', 'error');
      return;
    }

    setSending(true);
    try {
      await publishTraceabilityResults(teamCode);
      showToast?.(`Traceability results sent to ${teamCode}.`, 'success');
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setSending(false);
    }
  }

  return (
    <button type="button" className="btn btn--primary" onClick={handleSend} disabled={sending || !teamCode}>
      {sending ? 'Sending...' : 'Send'}
    </button>
  );
}

export default SendButton;
