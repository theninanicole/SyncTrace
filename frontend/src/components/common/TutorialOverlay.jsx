import { useEffect, useMemo, useRef, useState } from 'react';
import '../../styles/components/tutorial.css';

const VIEWPORT_PADDING = 12;
const TARGET_GAP = 12;
const DEFAULT_TOOLTIP_SIZE = { width: 340, height: 240 };
const FALLBACK_ORDER = ['bottom', 'top', 'right', 'left'];

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), Math.max(min, max));
}

function overlapArea(a, b) {
  const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  return w > 0 && h > 0 ? w * h : 0;
}

/**
 * Place the tooltip beside the target, preferring the step's placement, then
 * the opposite side, then any side that fits. Targets larger than the viewport
 * (tall columns, the matrix) use only their visible part; if no side fits, the
 * tooltip goes in the viewport corner that hides the least of the target.
 */
function computeTooltipPosition(rect, size, placement) {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const pad = VIEWPORT_PADDING;
  const width = Math.min(size.width, vw - pad * 2);
  const height = Math.min(size.height, vh - pad * 2);

  const visible = {
    left: Math.max(rect.left, 0),
    top: Math.max(rect.top, 0),
    right: Math.min(rect.right, vw),
    bottom: Math.min(rect.bottom, vh),
  };
  const midX = (visible.left + visible.right) / 2;
  const midY = (visible.top + visible.bottom) / 2;

  const candidates = {
    bottom: { top: visible.bottom + TARGET_GAP, left: midX - width / 2 },
    top: { top: visible.top - TARGET_GAP - height, left: midX - width / 2 },
    right: { top: midY - height / 2, left: visible.right + TARGET_GAP },
    left: { top: midY - height / 2, left: visible.left - TARGET_GAP - width },
  };
  const fits = {
    bottom: (c) => c.top + height <= vh - pad,
    top: (c) => c.top >= pad,
    right: (c) => c.left + width <= vw - pad,
    left: (c) => c.left >= pad,
  };
  const opposite = { bottom: 'top', top: 'bottom', left: 'right', right: 'left' };

  const preferred = FALLBACK_ORDER.includes(placement) ? placement : 'bottom';
  const order = [preferred, opposite[preferred], ...FALLBACK_ORDER]
    .filter((side, i, all) => all.indexOf(side) === i);

  const clampToViewport = (c) => ({
    top: clamp(c.top, pad, vh - height - pad),
    left: clamp(c.left, pad, vw - width - pad),
  });

  const side = order.find((s) => fits[s](candidates[s]));
  if (side) return clampToViewport(candidates[side]);

  const corners = [
    { top: vh - height - pad, left: vw - width - pad },
    { top: vh - height - pad, left: pad },
    { top: pad, left: vw - width - pad },
    { top: pad, left: pad },
  ].map(clampToViewport);
  return corners.reduce((best, c) => {
    const area = overlapArea(visible, { ...c, right: c.left + width, bottom: c.top + height });
    return area < best.area ? { ...c, area } : best;
  }, { ...corners[0], area: Infinity });
}

function getTargetRect(selector) {
  if (!selector) return null;
  const elements = document.querySelectorAll(selector);
  if (!elements.length) return null;

  const rects = Array.from(elements)
    .map((element) => element.getBoundingClientRect())
    .filter((rect) => rect.width > 0 && rect.height > 0);

  if (!rects.length) return null;

  const left = Math.min(...rects.map((rect) => rect.left));
  const top = Math.min(...rects.map((rect) => rect.top));
  const right = Math.max(...rects.map((rect) => rect.right));
  const bottom = Math.max(...rects.map((rect) => rect.bottom));

  return {
    left,
    top,
    width: right - left,
    height: bottom - top,
    right,
    bottom,
  };
}

function TutorialOverlay({
  steps = [],
  run = false,
  stepIndex = 0,
  onNext,
  onPrev,
  onClose,
}) {
  const [targetRect, setTargetRect] = useState(null);
  const [tooltipSize, setTooltipSize] = useState(DEFAULT_TOOLTIP_SIZE);
  const tooltipRef = useRef(null);

  useEffect(() => {
    if (!run) return;

    const updateRect = () => {
      setTargetRect(getTargetRect(steps[stepIndex]?.target));
    };

    const raf = requestAnimationFrame(updateRect);
    const interval = setInterval(updateRect, 200);
    window.addEventListener('resize', updateRect);

    return () => {
      cancelAnimationFrame(raf);
      clearInterval(interval);
      window.removeEventListener('resize', updateRect);
    };
  }, [run, stepIndex, steps]);

  // Track the tooltip's rendered size so placement accounts for long step text.
  useEffect(() => {
    const element = tooltipRef.current;
    if (!run || !element || typeof ResizeObserver === 'undefined') return;

    const observer = new ResizeObserver(() => {
      const { offsetWidth: width, offsetHeight: height } = element;
      setTooltipSize((prev) => (prev.width === width && prev.height === height ? prev : { width, height }));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [run, stepIndex]);

  // Bring off-screen targets into view; the delay lets a view switched by a
  // gated click render first.
  useEffect(() => {
    if (!run) return;
    const selector = steps[stepIndex]?.target;
    if (!selector || steps[stepIndex]?.center) return;

    const timer = setTimeout(() => {
      const element = Array.from(document.querySelectorAll(selector))
        .find((el) => el.getBoundingClientRect().width > 0);
      if (!element) return;
      const rect = element.getBoundingClientRect();
      if (rect.top >= 0 && rect.bottom <= window.innerHeight) return;
      const block = rect.height > window.innerHeight * 0.6 ? 'start' : 'center';
      element.scrollIntoView({ block, behavior: 'smooth' });
    }, 250);

    return () => clearTimeout(timer);
  }, [run, stepIndex, steps]);

  const step = steps[stepIndex];
  const hasPrev = stepIndex > 0;
  const hasNext = stepIndex < steps.length - 1;
  // Gated steps advance only when the student clicks the highlighted element itself.
  const clickSelector = step?.requireClick
    ? (typeof step.requireClick === 'string' ? step.requireClick : step.target)
    : null;

  useEffect(() => {
    if (!run || !clickSelector) return;

    // Capture phase runs before React's root listener, so clicks outside the
    // target are swallowed while the target's own onClick still fires.
    const handleClick = (event) => {
      if (tooltipRef.current?.contains(event.target)) return;
      if (event.target.closest?.(clickSelector)) {
        if (hasNext) onNext();
        else onClose();
        return;
      }
      event.preventDefault();
      event.stopPropagation();
    };

    window.addEventListener('click', handleClick, true);
    return () => window.removeEventListener('click', handleClick, true);
  }, [run, clickSelector, hasNext, onNext, onClose]);

  const tooltipStyle = useMemo(() => {
    // Centered when asked, or when the target isn't on screen (e.g. a button
    // that only appears once data exists).
    if (step?.center || !targetRect) {
      return { top: '50%', left: '50%', transform: 'translate(-50%, -50%)' };
    }
    const { top, left } = computeTooltipPosition(targetRect, tooltipSize, step?.placement);
    return { top, left };
  }, [targetRect, tooltipSize, step]);

  if (!run || !step) return null;

  return (
    <div
      className={`tutorial-overlay${clickSelector ? ' tutorial-overlay--gated' : ''}`}
      role="dialog"
      aria-modal="true"
      onClick={clickSelector ? undefined : (hasNext ? onNext : onClose)}
    >
      {(step.center || !targetRect) && !clickSelector && <div className="tutorial-overlay__backdrop" />}

      {targetRect && !step.center && (
        <div
          className="tutorial-spotlight"
          style={{
            top: targetRect.top + (targetRect.height / 2),
            left: targetRect.left + (targetRect.width / 2),
            width: targetRect.width + 8,
            height: targetRect.height + 8,
            transform: 'translate(-50%, -50%)',
          }}
        />
      )}

      <div
        ref={tooltipRef}
        className={`tutorial-tooltip${step.center ? ' tutorial-tooltip--center' : ''}`}
        style={tooltipStyle}
        onClick={(event) => {
          event.stopPropagation();
          if (clickSelector) return;
          if (hasNext) onNext();
          else onClose();
        }}
      >
        <div className="tutorial-tooltip__header">
          <span className="tutorial-tooltip__step">Step {stepIndex + 1} of {steps.length}</span>
          <button type="button" className="tutorial-tooltip__close" onClick={onClose} aria-label="Close tutorial">
            ×
          </button>
        </div>
        <div className="tutorial-tooltip__content">{step.content}</div>
        {stepIndex !== 0 && (
          <div className="tutorial-tooltip__hint">
            {clickSelector ? 'Click the highlighted button to continue' : 'Tap anywhere to continue'}
          </div>
        )}
        <div className="tutorial-tooltip__actions">
          {hasPrev && (
            <button
              type="button"
              className="btn"
              onClick={(event) => {
                event.stopPropagation();
                onPrev();
              }}
            >
              Back
            </button>
          )}
          <div className="tutorial-tooltip__spacer" />
          <button
            type="button"
            className="btn btn--ghost"
            onClick={(event) => {
              event.stopPropagation();
              onClose();
            }}
          >
            Skip
          </button>
        </div>
      </div>
    </div>
  );
}

export default TutorialOverlay;
