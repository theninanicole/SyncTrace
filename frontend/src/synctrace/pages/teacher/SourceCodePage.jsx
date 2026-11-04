import { useCallback, useEffect, useState } from 'react';
import PanelHeader from '../../../components/common/PanelHeader';
import ToastMessage from '../../../components/common/ToastMessage';
import { useToast } from '../../../hooks/useToast';
import { API_BASE_URL } from '../../../api';
import {
  analyzeSourceCodeAlignment,
  getGitHubRepositoryImpact,
  getLinkedGitHubRepositories,
  getTeamRepositories,
  getTraceComponents,
  ingestGitHubRepository,
  linkGitHubRepository,
  unlinkGitHubRepository,
  updateGitHubRepositoryRole,
} from '../../api';
import { componentLabel } from '../../constants';
import ComponentDetailModal from '../../components/teacher/ComponentDetailModal';
import LinkRepositoryDialog from '../../components/teacher/LinkRepositoryDialog';
import RepositoryCard from '../../components/teacher/RepositoryCard';
import UnlinkConfirmModal from '../../components/teacher/UnlinkConfirmModal';
import '../../components/common/TeamSelect.css';
import './SourceCodePage.css';
import './TraceabilityMappingPage.css';

function generateSessionId() {
  return crypto.randomUUID
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2) + Date.now().toString(36);
}

/**
 * @param {string} [teamCode] When set (student view), locks the page to this team
 *   and hides the team picker.
 */
function SourceCodePage({ onProgressRefresh, teamCode: lockedTeamCode = null }) {
  const isTeamLocked = Boolean(lockedTeamCode);
  const { toast, showToast, hideToast } = useToast();
  const [teams, setTeams] = useState([]);
  const [selectedTeam, setSelectedTeam] = useState(null);
  const [loadingTeams, setLoadingTeams] = useState(true);
  const [repositories, setRepositories] = useState([]);
  const [loadingRepositories, setLoadingRepositories] = useState(false);

  const [ingesting, setIngesting] = useState(false);
  const [ingestingRepositoryId, setIngestingRepositoryId] = useState(null);
  const [ingestedComponents, setIngestedComponents] = useState([]);
  const [ingestionProgress, setIngestionProgress] = useState({ step: '', message: '', percent: 0 });

  const [analyzing, setAnalyzing] = useState(false);
  const [findings, setFindings] = useState([]);
  const [alignmentProgress, setAlignmentProgress] = useState({ step: '', message: '', percent: 0 });
  const [showLinkDialog, setShowLinkDialog] = useState(false);
  const [linkingRepository, setLinkingRepository] = useState(false);
  const [repositoryActionId, setRepositoryActionId] = useState(null);
  const [unlinkTarget, setUnlinkTarget] = useState(null);
  const [unlinkImpact, setUnlinkImpact] = useState(null);
  const [loadingUnlinkImpact, setLoadingUnlinkImpact] = useState(false);
  const [previewComponent, setPreviewComponent] = useState(null);

  const loadTeams = useCallback(async () => {
    setLoadingTeams(true);
    try {
      const data = await getTeamRepositories();
      if (isTeamLocked) {
        const ownRows = data.filter((team) => team.teamCode === lockedTeamCode);
        const ownTeam = ownRows[ownRows.length - 1] || { teamCode: lockedTeamCode, githubUrl: '' };
        setTeams([ownTeam]);
        setSelectedTeam(ownTeam);
        return;
      }
      setTeams(data);
      setSelectedTeam(data[0] || null);
    } catch (err) {
      if (isTeamLocked) {
        const ownTeam = { teamCode: lockedTeamCode, githubUrl: '' };
        setTeams([ownTeam]);
        setSelectedTeam(ownTeam);
      } else {
        showToast(err.message, 'error');
      }
    } finally {
      setLoadingTeams(false);
    }
  }, [isTeamLocked, lockedTeamCode, showToast]);

  useEffect(() => {
    loadTeams();
  }, [loadTeams]);

  useEffect(() => {
    let cancelled = false;
    if (!selectedTeam?.teamCode) {
      setRepositories([]);
      setIngestedComponents([]);
      return () => { cancelled = true; };
    }

    setLoadingRepositories(true);
    Promise.all([
      getLinkedGitHubRepositories(selectedTeam.teamCode),
      getTraceComponents('IMPLEMENTATION', '', selectedTeam.teamCode),
    ])
      .then(([linked, components]) => {
        if (cancelled) return;
        setRepositories(linked);
        setIngestedComponents(
          components.filter((component) =>
            component.sourceType === 'GITHUB' && !component.sourceRemoved,
          ),
        );
      })
      .catch((err) => {
        if (!cancelled) showToast(err.message, 'error');
      })
      .finally(() => {
        if (!cancelled) setLoadingRepositories(false);
      });

    return () => { cancelled = true; };
  }, [selectedTeam?.teamCode, showToast]);

  async function refreshTeamData(teamCode = selectedTeam?.teamCode) {
    if (!teamCode) return;
    const [linked, components] = await Promise.all([
      getLinkedGitHubRepositories(teamCode),
      getTraceComponents('IMPLEMENTATION', '', teamCode),
    ]);
    setRepositories(linked);
    setIngestedComponents(
      components.filter((component) => component.sourceType === 'GITHUB' && !component.sourceRemoved),
    );
  }

  async function runIngestion({ repositoryId, all = false }) {
    if (!selectedTeam) {
      showToast('Please select a team', 'error');
      return;
    }

    const sessionId = generateSessionId();
    setIngesting(true);
    setIngestingRepositoryId(repositoryId || null);
    setRepositories((current) => current.map((repository) =>
      all || repository.id === repositoryId
        ? { ...repository, ingestionStatus: 'INGESTING', lastIngestError: null }
        : repository,
    ));
    setIngestionProgress({ step: 'RECEIVED', message: 'Starting ingestion...', percent: 0 });

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
      const data = await ingestGitHubRepository({
        repositoryId,
        all,
        teamCode: selectedTeam.teamCode,
        sessionId,
      });
      if (all) {
        const succeeded = data.results.filter((result) => result.status === 'INGESTED').length;
        const failed = data.results.length - succeeded;
        showToast(
          failed ? `Ingested ${succeeded} repository(s); ${failed} failed.`
            : `Ingested ${succeeded} repository(s).`,
          failed ? 'info' : 'success',
        );
      } else {
        showToast(`Ingested ${data.count} files`, 'success');
      }
      onProgressRefresh?.();
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      try {
        await refreshTeamData(selectedTeam.teamCode);
      } catch (err) {
        showToast(err.message, 'error');
      }
      setIngesting(false);
      setIngestingRepositoryId(null);
      es.close();
    }
  }

  async function handleLinkRepository({ githubUrl, role }) {
    if (!selectedTeam) return;
    setLinkingRepository(true);
    try {
      await linkGitHubRepository({ teamCode: selectedTeam.teamCode, githubUrl, role });
      setShowLinkDialog(false);
      showToast('Repository linked successfully', 'success');
      await refreshTeamData(selectedTeam.teamCode);
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setLinkingRepository(false);
    }
  }

  async function handleRoleChange(repository, role) {
    setRepositoryActionId(repository.id);
    try {
      const updated = await updateGitHubRepositoryRole({
        repositoryId: repository.id,
        teamCode: selectedTeam.teamCode,
        role,
      });
      setRepositories((current) => current.map((item) => item.id === updated.id ? updated : item));
      showToast('Repository role updated', 'success');
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setRepositoryActionId(null);
    }
  }

  async function handleUnlinkRequest(repository) {
    setUnlinkTarget(repository);
    setUnlinkImpact(null);
    setLoadingUnlinkImpact(true);
    try {
      const impact = await getGitHubRepositoryImpact({
        repositoryId: repository.id,
        teamCode: selectedTeam.teamCode,
      });
      setUnlinkImpact(impact);
    } catch (err) {
      setUnlinkTarget(null);
      showToast(err.message, 'error');
    } finally {
      setLoadingUnlinkImpact(false);
    }
  }

  async function handleUnlinkConfirm() {
    if (!unlinkTarget) return;
    setRepositoryActionId(unlinkTarget.id);
    try {
      await unlinkGitHubRepository({
        repositoryId: unlinkTarget.id,
        teamCode: selectedTeam.teamCode,
      });
      setUnlinkTarget(null);
      setUnlinkImpact(null);
      showToast('Repository unlinked; existing components and mappings were preserved', 'success');
      await refreshTeamData(selectedTeam.teamCode);
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setRepositoryActionId(null);
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

      {/* Student view embeds this page under its own header with the team fixed. */}
      {!isTeamLocked && (
        <>
          <PanelHeader
            title="Source Code"
            actions={
              <div className="teacher-header-actions">
                <button className="btn btn--soft" onClick={loadTeams} disabled={loadingTeams}>
                  Refresh Teams
                </button>
              </div>
            }
          />

          <div className="tm-team-filter-row">
            <label className="team-select">
              <select
                value={selectedTeam?.teamCode || ''}
                onChange={(e) => {
                  const team = teams.find(t => t.teamCode === e.target.value);
                  setSelectedTeam(team || null);
                }}
                disabled={loadingTeams || ingesting || analyzing}
                aria-label="Select team"
              >
                {teams.map((team) => (
                  <option key={team.teamCode} value={team.teamCode}>
                    {team.teamCode} {team.section && `(${team.section})`}
                  </option>
                ))}
              </select>
            </label>
          </div>
        </>
      )}

      <div className="sc-section sc-section--ingest">
        <div className="sc-section__header">
          <h3>GitHub Repositories</h3>
          <div className="sc-repository-toolbar">
            {repositories.length > 0 && (
              <button
                type="button"
                className="btn btn--soft"
                onClick={() => runIngestion({ all: true })}
                disabled={ingesting || loadingRepositories}
              >
                {ingesting && !ingestingRepositoryId ? 'Ingesting...' : 'Ingest All'}
              </button>
            )}
            <button
              type="button"
              className="btn btn--primary"
              onClick={() => setShowLinkDialog(true)}
              disabled={!selectedTeam || ingesting || linkingRepository}
            >
              + Link Repository
            </button>
          </div>
        </div>

        {loadingRepositories && <div className="sc-empty-state">Loading repositories...</div>}
        {!loadingRepositories && repositories.length === 0 && (
          <div className="sc-empty-state">
            No repositories are linked to this team yet.
          </div>
        )}
        {!loadingRepositories && repositories.length > 0 && (
          <div className="sc-repository-list">
            {repositories.map((repository) => (
              <RepositoryCard
                key={repository.id}
                repository={repository}
                busy={ingesting || linkingRepository || repositoryActionId === repository.id}
                onIngest={(item) => runIngestion({ repositoryId: item.id })}
                onRoleChange={handleRoleChange}
                onUnlink={handleUnlinkRequest}
              />
            ))}
          </div>
        )}

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

      <div className="sc-section sc-section--alignment">
        <div className="sc-section__header">
          <h3>Alignment Analysis</h3>
          <button
            className="btn btn--primary"
            onClick={handleAlignment}
            disabled={analyzing || ingestedComponents.length === 0}
          >
            {analyzing ? 'Analyzing...' : 'Run Alignment Analysis'}
          </button>
        </div>

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
        readOnly={isTeamLocked}
        onRenamed={(updated) => {
          setPreviewComponent(updated);
          setIngestedComponents((prev) =>
            prev.map((c) => (c.id === updated.id ? { ...c, ...updated } : c)),
          );
        }}
      />

      {showLinkDialog && selectedTeam && (
        <LinkRepositoryDialog
          teamCode={selectedTeam.teamCode}
          initialUrl={selectedTeam.githubUrl || ''}
          saving={linkingRepository}
          onClose={() => setShowLinkDialog(false)}
          onSubmit={handleLinkRepository}
        />
      )}
      <UnlinkConfirmModal
        repository={unlinkTarget}
        impact={unlinkImpact}
        loading={loadingUnlinkImpact}
        onCancel={() => {
          setUnlinkTarget(null);
          setUnlinkImpact(null);
        }}
        onConfirm={handleUnlinkConfirm}
      />
    </div>
  );
}

export default SourceCodePage;
