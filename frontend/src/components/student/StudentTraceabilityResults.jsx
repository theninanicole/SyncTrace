import { useCallback, useEffect, useState } from 'react';
import TraceabilityResults from '../../synctrace/components/common/TraceabilityResults';
import ComponentDetailModal from '../../synctrace/components/teacher/ComponentDetailModal';
import { useTraceabilityResultsData } from '../../synctrace/hooks/useTraceabilityResultsData';
import { getTraceabilityResultPublication } from '../../synctrace/api';
import '../../synctrace/pages/teacher/TraceabilityResultsPage.css';

function StudentTraceabilityResults({ teamCode }) {
  const [previewComponent, setPreviewComponent] = useState(null);
  const [publication, setPublication] = useState({ loading: true, published: false, publishedAt: null });
  const [statusError, setStatusError] = useState('');

  const loadPublication = useCallback(async () => {
    if (!teamCode) {
      setPublication({ loading: false, published: false, publishedAt: null });
      return false;
    }

    setPublication((current) => ({ ...current, loading: true }));
    setStatusError('');
    try {
      const data = await getTraceabilityResultPublication(teamCode);
      setPublication({
        loading: false,
        published: Boolean(data.published),
        publishedAt: data.publishedAt || null,
      });
      return Boolean(data.published);
    } catch (err) {
      setPublication({ loading: false, published: false, publishedAt: null });
      setStatusError(err.message || 'Failed to load traceability result status.');
      return false;
    }
  }, [teamCode]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadPublication();
  }, [loadPublication]);

  const {
    loading,
    rows,
    metrics,
    aiIssues,
    refresh,
  } = useTraceabilityResultsData(teamCode, { enabled: Boolean(teamCode) && publication.published });

  async function handleRefresh() {
    const published = await loadPublication();
    if (published) {
      refresh();
    }
  }

  return (
    <section className="card student-traceability-card">
      <div className="student-section-heading">
        <div>
          <h2 className="card__title">Traceability Results</h2>
          <p className="student-section-heading__subtitle">
            {teamCode
              ? `Coverage for ${teamCode} across SMART goals, documents, and implementation.`
              : 'Traceability coverage will appear after your team is connected.'}
          </p>
        </div>
        <button className="btn btn--soft" type="button" onClick={handleRefresh} disabled={publication.loading || loading || !teamCode}>
          {publication.loading || loading ? 'Checking...' : 'Refresh'}
        </button>
      </div>

      {publication.loading ? (
        <div className="student-traceability-locked">
          <h3 className="student-empty__title">Checking traceability access</h3>
          <p className="student-empty__text">Please wait while we check whether your professor has sent the results.</p>
        </div>
      ) : !publication.published ? (
        <div className="student-traceability-locked">
          <h3 className="student-empty__title">Traceability results are not available yet</h3>
          <p className="student-empty__text">
            {statusError || 'Your professor needs to send the traceability results before your team can view them here.'}
          </p>
        </div>
      ) : (
        <>
          <div className="tp-kpi-row student-traceability-kpis">
            <div className="tp-kpi tp-kpi--ok">
              <div className="tp-kpi__value">{metrics.alignmentPercent}%</div>
              <div className="tp-kpi__label">Alignment</div>
              <div className="tp-kpi__subtitle">Coverage across goals</div>
            </div>
            <div className="tp-kpi">
              <div className="tp-kpi__value">{metrics.unmappedGoals}</div>
              <div className="tp-kpi__label">Unmapped Goals</div>
              <div className="tp-kpi__subtitle">No traceability found</div>
            </div>
            <div className="tp-kpi">
              <div className="tp-kpi__value">{metrics.partialGoals}</div>
              <div className="tp-kpi__label">Partial Goals</div>
              <div className="tp-kpi__subtitle">Some document types mapped</div>
            </div>
            <div className="tp-kpi">
              <div className="tp-kpi__value">{metrics.missingCells}</div>
              <div className="tp-kpi__label">Missing Cells</div>
              <div className="tp-kpi__subtitle">Required traceability gaps</div>
            </div>
            <div className="tp-kpi">
              <div className="tp-kpi__value">{metrics.fullyCoveredGoals}</div>
              <div className="tp-kpi__label">Fully Covered</div>
              <div className="tp-kpi__subtitle">Goals with all doc types</div>
            </div>
          </div>

          <div className="tp-summary student-traceability-summary">
            <div className="tp-summary__text">
              {metrics.missingCells} traceability gap{metrics.missingCells !== 1 ? 's' : ''} remain across {rows.length} goal{rows.length !== 1 ? 's' : ''}.
            </div>
          </div>

          <TraceabilityResults
            loading={loading}
            rows={rows}
            onComponentClick={setPreviewComponent}
            aiIssues={aiIssues}
            issuesEmptyMessage="No published continuity issues for your team yet."
            readOnly
          />

          <ComponentDetailModal
            component={previewComponent}
            onClose={() => setPreviewComponent(null)}
            readOnly
          />
        </>
      )}
    </section>
  );
}

export default StudentTraceabilityResults;
