import { useEffect, useState } from 'react';
import { Download, RefreshCcw, Loader2 } from 'lucide-react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { API_BASE_URL } from '../../../api';
import { getTeamRepositories, ingestRepository, analyzeSourceCodeAlignment } from '../../api';
import { componentLabel } from '../../constants';
import ComponentDetailModal from '../../components/teacher/ComponentDetailModal';
import './SourceCodePage.css';
import './TraceabilityMappingPage.css';

function generateSessionId() {
  return crypto.randomUUID
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function SourceCodePage({ onProgressRefresh }) {
  const { toast, showToast, hideToast } = useToast();
  const [teams, setTeams] = useState([]);
  const [selectedTeam, setSelectedTeam] = useState(null);
  const [loadingTeams, setLoadingTeams] = useState(true);
  
  const [ingesting, setIngesting] = useState(false);
  const [ingestedComponents, setIngestedComponents] = useState([]);
  const [ingestionProgress, setIngestionProgress] = useState({ step: '', message: '', percent: 0 });
  
  const [analyzing, setAnalyzing] = useState(false);
  const [findings, setFindings] = useState([]);
  const [alignmentProgress, setAlignmentProgress] = useState({ step: '', message: '', percent: 0 });
  
  const [githubUrl, setGithubUrl] = useState('');
  const [previewComponent, setPreviewComponent] = useState(null);

  useEffect(() => {
    loadTeams();
  }, []);

  useEffect(() => {
    if (selectedTeam && selectedTeam.githubUrl) {
      setGithubUrl(selectedTeam.githubUrl);
    }
  }, [selectedTeam]);

  async function loadTeams() {
    setLoadingTeams(true);
    try {
      const data = await getTeamRepositories();
      setTeams(data);
      if (data.length > 0) {
        setSelectedTeam(data[0]);
      }
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setLoadingTeams(false);
    }
  }

  async function handleIngest() {
    if (!selectedTeam || !githubUrl) {
      showToast('Please select a team and enter a GitHub URL', 'error');
      return;
    }

    const sessionId = generateSessionId();
    setIngesting(true);
    setIngestionProgress({ step: 'RECEIVED', message: 'Starting ingestion...', percent: 0 });
    setIngestedComponents([]);

    const es = new EventSource(`${API_BASE_URL}/ai/progress/${sessionId}`);

    es.addEventListener('progress', (e) => {
      try {
        const { step, message, percent } = JSON.parse(e.data);
        setIngestionProgress({ step, message, percent });
      } catch { /* ignore parse errors */ }
    });

    es.addEventListener('done', () => {
      es.close();
    });

    es.addEventListener('error', (e) => {
      try {
        const { error: msg } = JSON.parse(e.data || '{}');
        if (msg) showToast(`Ingestion error: ${msg}`, 'error');
      } catch { /* ignore */ }
      es.close();
    });

    es.onerror = () => {
      es.close();
    };

    try {
      const data = await ingestRepository({ teamCode: selectedTeam.teamCode, githubUrl, sessionId });
      setIngestedComponents(data.components || []);
      showToast(`Ingested ${data.count} files`, 'success');
      onProgressRefresh?.();
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setIngesting(false);
      es.close();
    }
  }

  async function handleAlignment() {
    if (!selectedTeam) {
      showToast('Please select a team', 'error');
      return;
    }

    const sessionId = generateSessionId();
    setAnalyzing(true);
    setAlignmentProgress({ step: 'RECEIVED', message: 'Starting alignment analysis...', percent: 0 });
    setFindings([]);

    const es = new EventSource(`${API_BASE_URL}/ai/progress/${sessionId}`);

    es.addEventListener('progress', (e) => {
      try {
        const { step, message, percent } = JSON.parse(e.data);
        setAlignmentProgress({ step, message, percent });
      } catch { /* ignore parse errors */ }
    });

    es.addEventListener('done', () => {
      es.close();
    });

    es.addEventListener('error', (e) => {
      try {
        const { error: msg } = JSON.parse(e.data || '{}');
        if (msg) showToast(`Alignment error: ${msg}`, 'error');
      } catch { /* ignore */ }
      es.close();
    });

    es.onerror = () => {
      es.close();
    };

    try {
      const data = await analyzeSourceCodeAlignment(selectedTeam.teamCode, 'auto', sessionId);
      setFindings(data.findings || []);
      
      if (data.findings.length === 0) {
        if (ingestedComponents.length === 0) {
          showToast('No IMPLEMENTATION components found. Run GitHub ingestion first.', 'info');
        } else {
          showToast('No SDD components found for this team yet. AI-extracted SDD components are needed for alignment.', 'info');
        }
      } else {
        showToast(`Found ${data.count} alignment issues`, 'success');
      }
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setAnalyzing(false);
      es.close();
    }
  }

  const severityColors = {
    LOW: 'severity-low',
    MEDIUM: 'severity-medium',
    HIGH: 'severity-high',
    CRITICAL: 'severity-critical'
  };

  return (
    <div className="sc-root">
      <ToastMessage toast={toast} onClose={hideToast} />

      <PanelHeader
        title="Source Code"
        subtitle="Pull team GitHub files into Implementation components you can map to goals"
        actions={
          <div className="teacher-header-actions">
            <button className="btn btn--soft" onClick={loadTeams} disabled={loadingTeams}>
              <RefreshCcw size={14} /> Refresh Teams
            </button>
          </div>
        }
      />

      <div className="sc-team-selector">
        <label>Select Team:</label>
        <select
          value={selectedTeam?.teamCode || ''}
          onChange={(e) => {
            const team = teams.find(t => t.teamCode === e.target.value);
            setSelectedTeam(team || null);
          }}
          disabled={loadingTeams || ingesting || analyzing}
        >
          {teams.map((team) => (
            <option key={team.teamCode} value={team.teamCode}>
              {team.teamCode} {team.section && `(${team.section})`}
            </option>
          ))}
        </select>
      </div>

      <div className="sc-section">
        <h3>GitHub Repository Ingestion</h3>
        <div className="sc-input-row">
          <input
            type="text"
            placeholder="https://github.com/owner/repo"
            value={githubUrl}
            onChange={(e) => setGithubUrl(e.target.value)}
            disabled={ingesting}
            className="sc-input"
          />
          <button
            className="btn btn--primary"
            onClick={handleIngest}
            disabled={ingesting || !githubUrl}
          >
            {ingesting ? <Loader2 size={14} className="sc-spin" /> : <RefreshCcw size={14} />}
            {ingesting ? 'Ingesting...' : 'Ingest Repository'}
          </button>
        </div>

        {ingesting && (
          <div className="sc-progress">
            <div className="sc-progress-bar">
              <div className="sc-progress-fill" style={{ width: `${ingestionProgress.percent}%` }} />
            </div>
            <div className="sc-progress-text">
              {ingestionProgress.step}: {ingestionProgress.message} ({ingestionProgress.percent}%)
            </div>
          </div>
        )}

        {ingestedComponents.length > 0 && (
          <div className="sc-components">
            <h4>Ingested Files ({ingestedComponents.length})</h4>
            <div className="sc-component-list">
              {ingestedComponents.slice(0, 50).map((comp) => (
                <button
                  type="button"
                  key={comp.id}
                  className="sc-component-item sc-component-item--clickable"
                  onClick={() => setPreviewComponent(comp)}
                  title={comp.name || 'View source code'}
                >
                  <div className="sc-component-name">
                    {componentLabel(comp)}
                  </div>
                  <div className="sc-component-meta">
                    <span className="sc-badge">{comp.docType}</span>
                    {comp.createdAt && (
                      <span className="sc-date">
                        {new Date(comp.createdAt).toLocaleDateString()}
                      </span>
                    )}
                  </div>
                </button>
              ))}
              {ingestedComponents.length > 50 && (
                <div className="sc-component-item sc-component-item--more">
                  ... and {ingestedComponents.length - 50} more files
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      <div className="sc-section">
        <h3>Alignment Analysis</h3>
        <button
          className="btn btn--primary"
          onClick={handleAlignment}
          disabled={analyzing || ingestedComponents.length === 0}
        >
          {analyzing ? <Loader2 size={14} className="sc-spin" /> : <RefreshCcw size={14} />}
          {analyzing ? 'Analyzing...' : 'Run Alignment Analysis'}
        </button>

        {analyzing && (
          <div className="sc-progress">
            <div className="sc-progress-bar">
              <div className="sc-progress-fill" style={{ width: `${alignmentProgress.percent}%` }} />
            </div>
            <div className="sc-progress-text">
              {alignmentProgress.step}: {alignmentProgress.message} ({alignmentProgress.percent}%)
            </div>
          </div>
        )}

        {findings.length > 0 && (
          <div className="sc-findings">
            <h4>Alignment Issues ({findings.length})</h4>
            <div className="sc-finding-list">
              {findings.map((finding, index) => (
                <div key={index} className={`sc-finding-item ${severityColors[finding.severity] || 'severity-medium'}`}>
                  <div className="sc-finding-severity">{finding.severity}</div>
                  <div className="sc-finding-description">{finding.description}</div>
                  {finding.detectedAt && (
                    <div className="sc-finding-date">
                      Detected: {new Date(finding.detectedAt).toLocaleString()}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {!analyzing && findings.length === 0 && ingestedComponents.length > 0 && (
          <div className="sc-empty-state">
            <p>No alignment issues detected between SDD and IMPLEMENTATION components.</p>
          </div>
        )}

        {!analyzing && findings.length === 0 && ingestedComponents.length === 0 && (
          <div className="sc-empty-state">
            <p>Ingest a GitHub repository first to enable alignment analysis.</p>
          </div>
        )}
      </div>

      <ComponentDetailModal
        component={previewComponent}
        onClose={() => setPreviewComponent(null)}
        showToast={showToast}
        onRenamed={(updated) => {
          setPreviewComponent(updated);
          setIngestedComponents((prev) =>
            prev.map((c) => (c.id === updated.id ? { ...c, ...updated } : c)),
          );
        }}
      />
    </div>
  );
}

export default SourceCodePage;
