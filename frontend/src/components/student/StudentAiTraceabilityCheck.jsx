import { useState } from 'react';
import { generateAiTraceabilityMapping } from '../../synctrace/api';
import { STAGES } from '../../synctrace/constants';

function StudentAiTraceabilityCheck({ teamCode, onComplete, showToast }) {
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(null);

  async function handleRun() {
    if (!teamCode || running) return;
    setRunning(true);
    let totalLinks = 0;
    try {
      for (let index = 0; index < STAGES.length; index += 1) {
        const stage = STAGES[index];
        setProgress({ current: index + 1, total: STAGES.length, label: stage.label });
        const result = await generateAiTraceabilityMapping(teamCode, stage.key);
        totalLinks += result.createdLinks || 0;
      }
      showToast?.(`AI traceability check complete - ${totalLinks} link(s) created.`, 'success');
      onComplete?.();
    } catch (error) {
      showToast?.(error.message || 'AI traceability check failed.', 'error');
    } finally {
      setRunning(false);
      setProgress(null);
    }
  }

  return (
    <section className="card student-ai-check-card">
      <div className="student-section-heading">
        <div>
          <h2 className="card__title">AI Traceability Self-Check</h2>
          <p className="student-section-heading__subtitle">
            Run the AI mapping on your team's sent documents to check whether they are properly traceable, independent of your professor's official results below.
          </p>
          {progress && <p className="student-section-heading__subtitle">Stage {progress.current}/{progress.total}: {progress.label}</p>}
        </div>
        <button className="btn btn--primary" type="button" onClick={handleRun} disabled={running || !teamCode}>
          {running ? `Stage ${progress?.current || 1}/${progress?.total || STAGES.length}...` : 'Run AI Check'}
        </button>
      </div>
    </section>
  );
}

export default StudentAiTraceabilityCheck;
