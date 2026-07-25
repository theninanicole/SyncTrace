import { useState } from 'react';
import { exportAuditReport } from '../../api';

function ExportReportButton({ showToast, teamCode }) {
  const [format, setFormat] = useState('json');
  const [loading, setLoading] = useState(false);

  const handleExport = async () => {
    if (!teamCode) {
      showToast('No team selected for export', 'error');
      return;
    }

    setLoading(true);
    try {
      await exportAuditReport(teamCode, format);
      showToast(`Exported report as ${format.toUpperCase()}`, 'success');
    } catch (err) {
      showToast(err.message || 'Export failed', 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
      <select
        value={format}
        onChange={(e) => setFormat(e.target.value)}
        disabled={loading}
        style={{ padding: '0.5rem', borderRadius: '4px', border: '1px solid #ccc' }}
      >
        <option value="json">JSON</option>
        <option value="csv">CSV</option>
        <option value="pdf">PDF</option>
      </select>
      <button
        type="button"
        className="btn btn--primary"
        onClick={handleExport}
        disabled={loading}
      >
        {loading ? 'Exporting...' : 'Export Report'}
      </button>
    </div>
  );
}

export default ExportReportButton;
