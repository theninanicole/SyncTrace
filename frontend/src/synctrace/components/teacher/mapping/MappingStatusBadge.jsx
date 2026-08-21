const STATUS_VARIANT = {
  'Not Started': 'neutral',
  'In Progress': 'progress',
  Mapped: 'ok',
  'Needs Attention': 'warn',
};

function MappingStatusBadge({ status }) {
  const variant = STATUS_VARIANT[status] || 'neutral';
  return (
    <span className={`stm-status-badge stm-status-badge--${variant}`}>
      {status}
    </span>
  );
}

export default MappingStatusBadge;
