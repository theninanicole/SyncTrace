import { useEffect, useRef, useState } from 'react';
import AppModal from '../../../../components/common/AppModal';
import { ARTIFACT_KINDS_BY_DOC_TYPE, DOC_TYPE_LABELS } from '../../../constants';

const EMPTY_FORM = { name: '', type: '', description: '', codeName: '', imageData: '' };

function AddComponentDialog({ isOpen, onClose, docType, onAdd }) {
  const [form, setForm] = useState(EMPTY_FORM);
  const fileInputRef = useRef(null);

  const artifactOptions = ARTIFACT_KINDS_BY_DOC_TYPE[docType] || [];

  useEffect(() => {
    if (!isOpen) return;
    setForm({ ...EMPTY_FORM, type: artifactOptions[0]?.value || '' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, docType]);

  if (!isOpen) return null;

  function handleChange(field, value) {
    setForm((prev) => ({ ...prev, [field]: value }));
  }

  function handleImageChange(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const base64 = String(reader.result).split(',')[1] || '';
      handleChange('imageData', base64);
    };
    reader.readAsDataURL(file);
  }

  function clearImage() {
    handleChange('imageData', '');
    if (fileInputRef.current) fileInputRef.current.value = '';
  }

  function handleSubmit() {
    if (!form.name.trim()) return;
    onAdd({ ...form });
    onClose();
  }

  return (
    <AppModal
      isOpen={isOpen}
      onClose={onClose}
      title={`Add ${DOC_TYPE_LABELS[docType] || docType} Component`}
      subtitle="Manually add a component to the library for this document type."
      footer={
        <div className="modal-actions" style={{ justifyContent: 'flex-end', width: '100%' }}>
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn btn--primary" disabled={!form.name.trim()} onClick={handleSubmit}>
            Add Component
          </button>
        </div>
      }
    >
      <div className="stm-form">
        <label className="tm-field-label">Component name / title</label>
        <input
          className="tm-input"
          placeholder="e.g. FR-04 Password Reset"
          value={form.name}
          onChange={(e) => handleChange('name', e.target.value)}
        />

        <label className="tm-field-label">Component type / category</label>
        <select className="tm-select" value={form.type} onChange={(e) => handleChange('type', e.target.value)}>
          {artifactOptions.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>

        <label className="tm-field-label">Component image (optional)</label>
        {form.imageData ? (
          <div className="stm-form__image-preview">
            <img src={`data:image/jpeg;base64,${form.imageData}`} alt="Component preview" />
            <button type="button" className="stm-mini-btn" onClick={clearImage}>Remove image</button>
          </div>
        ) : (
          <input
            ref={fileInputRef}
            className="tm-input"
            type="file"
            accept="image/*"
            onChange={handleImageChange}
          />
        )}

        <label className="tm-field-label">Notes / content summary</label>
        <textarea
          className="tm-input"
          rows={3}
          placeholder="Briefly describe what this component covers…"
          value={form.description}
          onChange={(e) => handleChange('description', e.target.value)}
        />

        <label className="tm-field-label">Component code (optional)</label>
        <input
          className="tm-input"
          placeholder="e.g. UC-04"
          value={form.codeName}
          onChange={(e) => handleChange('codeName', e.target.value)}
        />
      </div>
    </AppModal>
  );
}

export default AddComponentDialog;
