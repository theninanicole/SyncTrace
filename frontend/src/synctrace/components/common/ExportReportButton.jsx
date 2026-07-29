import { useState } from 'react';
import AppModal from '../../../components/common/AppModal';
import { exportAuditReport } from '../../api';

const FORMAT_OPTIONS = [
  { value: 'json', label: 'JSON', description: 'Structured data, ideal for tooling or archiving.' },
  { value: 'csv', label: 'CSV', description: 'Spreadsheet-friendly, opens in Excel or Sheets.' },
  { value: 'pdf', label: 'PDF', description: 'Formatted document, ready to share or print.' },
];

function ExportReportButton({ showToast, teamCode }) {
  const [isOpen, setIsOpen] = useState(false);
  const [format, setFormat] = useState('json');
  const [loading, setLoading] = useState(false);

  const openModal = () => {
    if (!teamCode) {
      showToast('No team selected for export', 'error');
      return;
    }
    setIsOpen(true);
  };

  const closeModal = () => {
    if (loading) return;
    setIsOpen(false);
  };

  const handleExport = async () => {
    setLoading(true);
    try {
      await exportAuditReport(teamCode, format);
      showToast(`Exported report as ${format.toUpperCase()}`, 'success');
      setIsOpen(false);
    } catch (err) {
      showToast(err.message || 'Export failed', 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className="btn btn--primary"
        onClick={openModal}
        style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem' }}
      >
        Export Report
      </button>

      <AppModal
        isOpen={isOpen}
        onClose={closeModal}
        title="Export Report"
        subtitle={`Choose a format to export the report for ${teamCode || 'the selected team'}.`}
        footer={
          <div className="modal-actions modal-actions--end" style={{ width: '100%' }}>
            <button className="btn" onClick={closeModal} disabled={loading}>Cancel</button>
            <button className="btn btn--primary" onClick={handleExport} disabled={loading}>
              {loading ? 'Exporting...' : `Export as ${format.toUpperCase()}`}
            </button>
          </div>
        }
      >
        <div className="export-format-options">
          {FORMAT_OPTIONS.map((option) => {
            const { value, label, description } = option;
            const checked = format === value;
            return (
              <label
                key={value}
                className={`export-format-option ${checked ? 'export-format-option--checked' : ''}`}
              >
                <input
                  type="radio"
                  name="export-format"
                  value={value}
                  checked={checked}
                  disabled={loading}
                  onChange={() => setFormat(value)}
                />
                <span className="export-format-option__text">
                  <span className="export-format-option__label">{label}</span>
                  <span className="export-format-option__description">{description}</span>
                </span>
              </label>
            );
          })}
        </div>
      </AppModal>
    </>
  );
}

export default ExportReportButton;
