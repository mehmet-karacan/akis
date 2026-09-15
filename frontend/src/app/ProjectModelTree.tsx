import { useEffect, useState } from 'react'
import { ChevronDown, ChevronRight, Database, ExternalLink } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { topologyApi, type Model, type DataObject, type Submodel } from '../features/topology/api'
import { ModelObjectTree } from '../features/models/ModelObjectTree'
import { projectRoute } from '../features/projects/CurrentProjectContext'

function ModelBranch({ model, projectUuid, onNavigate }: { model: Model; projectUuid: string; onNavigate(path: string): void }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [folders, setFolders] = useState<Submodel[]>([])
  const [objects, setObjects] = useState<DataObject[] | null>(null)
  const [error, setError] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => { const refresh = () => setRetry((value) => value + 1); window.addEventListener('akis:models-changed', refresh); return () => window.removeEventListener('akis:models-changed', refresh) }, [])
  useEffect(() => {
    if (!open) return
    let active = true
    setError(false)
    void Promise.all([topologyApi.listDataObjects(projectUuid, model.uuid), topologyApi.listSubmodels(projectUuid, model.uuid)]).then(([rows, groups]) => { if (active) { setObjects(rows); setFolders(groups) } }).catch(() => { if (active) setError(true) })
    return () => { active = false }
  }, [open, projectUuid, model.uuid, retry])
  return <li className="sidebar-folder"><div className="sidebar-folder-action-row"><button type="button" className="sidebar-folder-row" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? <ChevronDown /> : <ChevronRight />}<Database /><span>{model.name}</span><small>{model.dataObjectCount ?? objects?.length ?? ''}</small></button><button type="button" className="sidebar-object-menu-button sidebar-object-menu-button--always" aria-label={`${t('nav.openObject')}: ${model.name}`} onClick={() => onNavigate(projectRoute(`/models/${model.uuid}`))}><ExternalLink /></button></div>{open && <ul>{error ? <li><button type="button" onClick={() => setRetry(retry + 1)}>{t('common.retry')}</button></li> : objects === null ? <li>{t('common.loading')}</li> : objects.length === 0 && folders.length === 0 ? <li className="sidebar-group-empty">{t('models.noDataObjects')}</li> : <li><ModelObjectTree folders={folders} objects={objects} onSelect={(uuid) => onNavigate(projectRoute(`/models/${model.uuid}?object=${encodeURIComponent(uuid)}`))} /></li>}</ul>}</li>
}

export function ProjectModelTree({ projectUuid, onNavigate }: { projectUuid: string; onNavigate(path: string): void }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [models, setModels] = useState<Model[] | null>(null)
  const [error, setError] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    if (!open) return
    let active = true
    setModels(null); setError(false)
    void topologyApi.listModels(projectUuid).then((rows) => { if (active) setModels(rows) }).catch(() => { if (active) setError(true) })
    return () => { active = false }
  }, [open, projectUuid, retry])
  return <li className="sidebar-folder sidebar-model-link"><div className="sidebar-folder-action-row"><button type="button" className="sidebar-folder-row" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? <ChevronDown /> : <ChevronRight />}<Database /><span>{t('nav.models')}</span></button><button type="button" className="sidebar-object-menu-button sidebar-object-menu-button--always" aria-label={`${t('nav.openObject')}: ${t('models.title')}`} onClick={() => onNavigate(projectRoute('/models'))}><ExternalLink /></button></div>{open && <ul>{error ? <li><button type="button" onClick={() => setRetry(retry + 1)}>{t('common.retry')}</button></li> : models === null ? <li>{t('common.loading')}</li> : models.length === 0 ? <li className="sidebar-group-empty">{t('models.empty')}</li> : models.map((model) => <ModelBranch key={model.uuid} model={model} projectUuid={projectUuid} onNavigate={onNavigate} />)}</ul>}</li>
}
