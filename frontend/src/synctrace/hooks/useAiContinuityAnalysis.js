import { useState } from 'react';
import { API_BASE_URL } from '../../api';
import { detectContinuityGaps, generateDiagnosticRecommendations } from '../api';

function generateSessionId() {
  return crypto.randomUUID
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2) + Date.now().toString(36);
}

/**
 * Runs AI continuity analysis (gap detection + diagnostic recommendations) for one team,
 * streaming progress from the backend. Shared by the teacher and student results views.
 */
export function useAiContinuityAnalysis(teamCode, { showToast, setAiFindings, setAiRecommendations, onComplete }) {
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState({ step: '', message: '', percent: 0 });

  async function run() {
    if (!teamCode) {
      showToast?.('Select a team above to run AI analysis.', 'error');
      return;
    }

    const sessionId = generateSessionId();
    setRunning(true);
    setProgress({ step: 'RECEIVED', message: 'Starting AI continuity analysis...', percent: 0 });
    setAiFindings([]);
    setAiRecommendations([]);

    const es = new EventSource(`${API_BASE_URL}/ai/progress/${sessionId}`);

    es.addEventListener('progress', (e) => {
      try {
        const { step, message, percent } = JSON.parse(e.data);
        setProgress({ step, message, percent });
      } catch { /* ignore parse errors */ }
    });

    es.addEventListener('done', () => {
      es.close();
    });

    es.addEventListener('error', (e) => {
      try {
        const { error: msg } = JSON.parse(e.data || '{}');
        if (msg) showToast?.(`AI analysis error: ${msg}`, 'error');
      } catch { /* ignore */ }
      es.close();
    });

    es.onerror = () => {
      es.close();
    };

    try {
      // Both detectContinuityGaps and generateDiagnosticRecommendations are scoped to a
      // single team on the backend: gap detection only counts components/mappings that
      // belong to teamCode (goals with none are skipped), and recommendations are generated
      // only from findings already tagged with that teamCode. Students are always scoped
      // to their own team server-side regardless of the teamCode sent.
      setProgress({ step: 'DETECTING', message: 'Detecting continuity gaps...', percent: 30 });
      const gapsData = await detectContinuityGaps(teamCode, null);
      setAiFindings(gapsData.findings || []);

      setProgress({ step: 'RECOMMENDING', message: 'Generating diagnostic recommendations...', percent: 60 });
      const recommendationsData = await generateDiagnosticRecommendations(teamCode, 'auto', sessionId);
      setAiRecommendations(recommendationsData.recommendations || []);
      await onComplete?.();

      setProgress({ step: 'COMPLETE', message: 'AI analysis complete', percent: 100 });

      if (gapsData.findings.length === 0 && recommendationsData.recommendations.length === 0) {
        showToast?.('AI analysis complete. No continuity gaps or recommendations found.', 'success');
      } else {
        showToast?.(`AI analysis complete. Found ${gapsData.count} gap(s) and ${recommendationsData.count} recommendation(s).`, 'success');
      }
    } catch (err) {
      showToast?.(err.message, 'error');
    } finally {
      setRunning(false);
      es.close();
    }
  }

  return { running, progress, run };
}
