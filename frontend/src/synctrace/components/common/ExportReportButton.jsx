function ExportReportButton({ showToast }) {
  return (
    <button type="button" className="btn btn--primary" onClick={() => showToast('Export coming soon', 'info')}>
      Export Report
    </button>
  );
}

export default ExportReportButton;
