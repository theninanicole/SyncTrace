import { Info } from 'lucide-react';

/** Keeps guidance one hover or keyboard focus away instead of a paragraph on the page. */
function InfoHint({ text }) {
  return (
    <span className="info-hint" role="img" aria-label={text} title={text} tabIndex={0}>
      <Info size={15} aria-hidden="true" />
    </span>
  );
}

export default InfoHint;
