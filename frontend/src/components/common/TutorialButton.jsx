import { CircleHelp } from 'lucide-react';
import '../../styles/components/tutorial.css';

function TutorialButton({ onClick, label = 'Quick Tutorial' }) {
  return (
    <button
      type="button"
      className="tutorial-button"
      onClick={onClick}
      aria-label={label}
    >
      <CircleHelp size={16} aria-hidden="true" />
      <span>{label}</span>
    </button>
  );
}

export default TutorialButton;
