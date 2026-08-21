function MappingStageSelector({ stages, selectedStage, onChange }) {
  return (
    <label className="stm-stage-select">
      <span className="stm-stage-select__label">Stage</span>
      <select value={selectedStage} onChange={(e) => onChange(e.target.value)}>
        {stages.map((s) => (
          <option key={s.key} value={s.key}>{s.label}</option>
        ))}
      </select>
    </label>
  );
}

export default MappingStageSelector;
