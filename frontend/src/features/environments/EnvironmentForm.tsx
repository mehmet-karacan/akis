import { Input, Switch } from 'antd'
import { CircleAlert, LoaderCircle, Save } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '../../core/ui'
import { Select as FormSelect } from '../../core/ui/Select'
import { topologyApi, type Environment, type EnvironmentRisk } from '../topology/api'
import { ENVIRONMENT_RISKS, riskLabel } from './environmentCatalog'
import '../topology/topology.css'

interface Props {
  projectUuid: string
  onCreated(environment: Environment): Promise<void> | void
  onClose(): void
}

/** Creates an environment (ODI context): code, name, risk class and whether it is the default context. */
export function EnvironmentForm({ projectUuid, onCreated, onClose }: Props) {
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [risk, setRisk] = useState<EnvironmentRisk>('DUSUK')
  const [defaultEnvironment, setDefaultEnvironment] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!name.trim() || !/^[A-Za-z][A-Za-z0-9_]{0,99}$/.test(code)) { setError(tr ? 'Ad ve kod zorunludur.' : 'Name and code are required.'); return }
    setBusy(true); setError('')
    try {
      const created = await topologyApi.createEnvironment(projectUuid, {
        code: code.trim().toUpperCase(), name: name.trim(), description: description.trim() || null, risk, defaultEnvironment,
      })
      await onCreated(created)
      onClose()
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }

  return <form className="topology-connection-form topology-connection-form--simple" onSubmit={(event) => void save(event)}>
    <fieldset disabled={busy} className="connection-editor-fields"><legend className="sr-only">{t('schemas.addEnvironment')}</legend>
    <div className="topology-form topology-form--grid">
      <label className="topology-field"><span>{t('schemas.name')} *</span><Input required value={name} onChange={(e) => setName(e.target.value)} /></label>
      <label className="topology-field"><span>{t('schemas.code')} *</span><Input required pattern="[A-Za-z][A-Za-z0-9_]{0,99}" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} /></label>
      <label className="topology-field"><span>{tr ? 'Risk Sınıfı' : 'Risk Class'} *</span>
        <FormSelect value={risk} onChange={(event) => setRisk(event.target.value as EnvironmentRisk)}>
          {ENVIRONMENT_RISKS.map((item) => <option key={item} value={item}>{riskLabel(item, tr)}</option>)}
        </FormSelect>
      </label>
      <label className="topology-field"><span>{tr ? 'Varsayılan Ortam' : 'Default Environment'}</span><span><Switch checked={defaultEnvironment} onChange={setDefaultEnvironment} /></span></label>
      <label className="topology-field topology-field--wide"><span>{t('schemas.description')}</span><Input.TextArea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
      <p className="form-note topology-field--wide">{t('schemas.environmentSimpleHint')}</p>
    </div>
    </fieldset>
    {error && <div className="topology-inline-error" role="alert"><CircleAlert />{error}</div>}
    <footer className="topology-form-actions">
      <Button tone="ghost" className="topology-button topology-button--quiet" type="button" onClick={onClose}>{t('common.cancel')}</Button>
      <Button tone="primary" className="topology-button" type="submit" disabled={busy}>{busy ? <LoaderCircle className="is-spinning" /> : <Save />}{t('schemas.create')}</Button>
    </footer>
  </form>
}
