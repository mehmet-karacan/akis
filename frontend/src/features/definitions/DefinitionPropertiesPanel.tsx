import { Checkbox, Input } from 'antd'
import { Cpu, Save } from 'lucide-react'
import { Select as FormSelect } from '../../core/ui/Select'
import { DATABASE_TYPES } from '../topology/connectionFormModel'
import { databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { useState, type FormEvent } from 'react'
import { Button } from '../../core/ui/Button'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { definitionsApi } from './api'
import { useDefinitionsI18n } from './i18n'
import type { Definition } from './types'

interface Props {
  projectUuid: string
  definition: Definition
  canWrite: boolean
  /** Procedure technology (ODI Definition tab); saved into the draft, steps then pick logical schemas of that technology. */
  technology?: { source?: string; target?: string; multiConnection?: boolean }
  onTechnologyChange?(technology: { source?: string; target?: string; multiConnection?: boolean }): void
  onUpdated(definition: Definition): void
}

/** ODI "Definition" tab: name and description are editable; code, type and folder are identity and stay fixed. */
export function DefinitionPropertiesPanel({ projectUuid, definition, canWrite, technology, onTechnologyChange, onUpdated }: Props) {
  const { language, t } = useDefinitionsI18n()
  const tr = language.startsWith('tr')
  const [name, setName] = useState(definition.name)
  const [description, setDescription] = useState(definition.description ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const dirty = name.trim() !== definition.name || (description.trim() || '') !== (definition.description ?? '')

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!canWrite || !name.trim() || busy) return
    setBusy(true); setError('')
    try {
      const updated = await definitionsApi.updateDefinition(projectUuid, definition.uuid, { name: name.trim(), description: description.trim() || null, expectedVersion: definition.version })
      notifyFeedback(tr ? 'Tanım kaydedildi.' : 'Definition saved.')
      onUpdated(updated)
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('requestError')) }
    finally { setBusy(false) }
  }


  return <form className="definition-properties" onSubmit={(event) => void save(event)}>
    <fieldset disabled={!canWrite || busy} className="definition-properties-fields">
      <div className="form-grid two-column">
        <label>{t('name')} *<Input required value={name} onChange={(event) => setName(event.target.value)} /></label>
        <label>{t('code')}<Input value={definition.code} readOnly /></label>
        {technology && onTechnologyChange && <>
          <label className="form-grid-wide definition-multi-connection"><Checkbox checked={technology.multiConnection !== false} onChange={(event) => onTechnologyChange(event.target.checked ? { ...technology, multiConnection: true } : { ...technology, multiConnection: false, source: technology.target ?? technology.source, target: technology.target ?? technology.source })} /><span>{tr ? 'Çift bağlantı' : 'Multi-connections'}</span></label>
          {technology.multiConnection === false ? <label>{tr ? 'Teknoloji' : 'Technology'}<span className="procedure-technology-field"><Cpu size={14} aria-hidden="true" /><FormSelect value={technology.target ?? ''} onChange={(event) => onTechnologyChange({ ...technology, source: event.target.value || undefined, target: event.target.value || undefined })}><option value="">{tr ? 'Seçilmedi' : 'Not selected'}</option>{DATABASE_TYPES.map((type) => <option key={type} value={type}>{databaseProviderVisual(type).label}</option>)}</FormSelect></span></label> : <>
          <label>{t('sourceTechnology')}<span className="procedure-technology-field"><Cpu size={14} aria-hidden="true" /><FormSelect value={technology.source ?? ''} onChange={(event) => onTechnologyChange({ ...technology, source: event.target.value || undefined })}><option value="">{tr ? 'Seçilmedi' : 'Not selected'}</option>{DATABASE_TYPES.map((type) => <option key={type} value={type}>{databaseProviderVisual(type).label}</option>)}</FormSelect></span></label>
          <label>{t('targetTechnology')}<span className="procedure-technology-field"><Cpu size={14} aria-hidden="true" /><FormSelect value={technology.target ?? ''} onChange={(event) => onTechnologyChange({ ...technology, target: event.target.value || undefined })}><option value="">{tr ? 'Seçilmedi' : 'Not selected'}</option>{DATABASE_TYPES.map((type) => <option key={type} value={type}>{databaseProviderVisual(type).label}</option>)}</FormSelect></span></label>
          </>}
        </>}
        <label className="form-grid-wide">{t('description')}<Input.TextArea rows={4} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
      </div>
    </fieldset>
    {error && <div className="error-banner" role="alert">{error}</div>}
    {canWrite && <footer className="topology-form-actions topology-form-actions--right">
      <Button type="submit" tone="primary" icon={<Save size={16} />} busy={busy} disabled={!dirty || !name.trim()}>{t('saveDraft')}</Button>
    </footer>}
  </form>
}
