import { useEffect, useRef, useState } from 'react';
import '../../../../styles/components/teacher-filter-panel.css';

/**
 * One "Extract" header button. In the Proposal → SRS stage it opens a dropdown to pick
 * SMART Goals or Components; every other stage only has components, so it extracts directly.
 */
function ExtractMenu({ isProposalStage, onExtractGoals, onExtractComponents, className = '' }) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => {
      if (e.type === 'keydown' ? e.key === 'Escape' : !rootRef.current?.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', close);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', close);
    };
  }, [open]);

  if (!isProposalStage) {
    return (
      <button className={`btn btn--soft ${className}`} type="button" onClick={onExtractComponents}>
        Extract Components
      </button>
    );
  }

  const pick = (fn) => () => { setOpen(false); fn(); };

  return (
    <div className="custom-dropdown stm-extract-menu" ref={rootRef}>
      <button
        className={`btn btn--soft ${className}`}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        Extract <span className="custom-dropdown__arrow" aria-hidden="true">▼</span>
      </button>
      {open && (
        <ul className="custom-dropdown__list stm-extract-menu__list" role="menu">
          <li className="custom-dropdown__item" role="menuitem" tabIndex={0} onClick={pick(onExtractGoals)}
            onKeyDown={(e) => e.key === 'Enter' && pick(onExtractGoals)()}>
            SMART Goals
          </li>
          <li className="custom-dropdown__item" role="menuitem" tabIndex={0} onClick={pick(onExtractComponents)}
            onKeyDown={(e) => e.key === 'Enter' && pick(onExtractComponents)()}>
            Components
          </li>
        </ul>
      )}
    </div>
  );
}

export default ExtractMenu;
