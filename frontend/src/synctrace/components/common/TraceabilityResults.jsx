import TraceabilityMatrixTable from './TraceabilityMatrix';
import '../../pages/teacher/TraceabilityMappingPage.css';

function TraceabilityResults({ loading, rows, onComponentClick }) {
  return (
    <>
      {loading ? (
        <p className="tm-muted">Loading traceability matrix...</p>
      ) : (
        <TraceabilityMatrixTable
          rows={rows}
          onComponentClick={onComponentClick}
          emptyMessage="No SMART goals yet."
        />
      )}
    </>
  );
}

export default TraceabilityResults;
