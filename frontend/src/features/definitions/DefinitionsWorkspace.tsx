import { Select as FormSelect } from '../../core/ui/Select'
import { Button as AntActionButton } from '../../core/ui/Button'
import { Input as AntInput } from 'antd'
import {
  AlertCircle,
  Check,
  ChevronRight,
  FileCode2,
  FolderInput,
  GitBranch,
  LoaderCircle,
  Plus,
  Save,
} from 'lucide-react'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ApiProblem } from '../../core/api/client'
import { usePendingChanges } from '../../core/navigation/PendingChangesContext'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { Dialog } from '../../core/ui/Dialog'
import { DataGrid } from '../../core/ui/DataGrid'
import { executionApi } from '../execution/api'
import type { ProjectCapabilities } from '../execution/types'
import { operationsApi } from '../operations/api'
import type { Publication } from '../operations/types'
import { topologyApi, type Environment, type LogicalSchema } from '../topology/api'
import { definitionsApi } from './api'
import { createDefaultContent, isMappingContent, isProcedureContent } from './defaults'
import { definitionTypeKey, useDefinitionsI18n } from './i18n'
import { MappingGrid } from './MappingGrid'
import { PreRunReport } from './PreRunReport'
import { PackageSimulationReport, ProcedureSimulationReport } from './SimulationReports'
import { editMapping, migrateLegacyMapping, storeMapping, initialSchemaVersion } from './mappingAuthoring'
import { PackageEditor } from './PackageEditor'
import { ProcedureEditor } from './ProcedureEditor'
import { StructuredDraftEditor } from './StructuredDraftEditor'
import { VariableHistory } from './VariableHistory'
import type {
  Definition,
  DefinitionType,
  DefinitionTypeDescriptor,
  DefinitionVersion,
  Draft,
  Folder,
  NewDefinitionInput,
  NewFolderInput,
  MoveDefinitionInput,
  MoveFolderInput,
  Scenario,
} from './types'
import { DEFINITION_TYPES } from './types'
import { DefinitionCatalog } from './DefinitionCatalog'
import { DefinitionTypeIcon, ProjectFolderIcon } from './DefinitionTypeIcon'
import { useDocumentTab } from '../../app/DocumentTabsContext'
import { DefinitionPropertiesPanel } from './DefinitionPropertiesPanel'
import { DATABASE_TYPES } from '../topology/connectionFormModel'
import { databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { Layers, ListOrdered, SlidersHorizontal, Workflow } from 'lucide-react'
import './definitions.css'

const notifyProjectTreeChanged = () => window.dispatchEvent(new Event('akis:definitions-changed'))

interface DefinitionsWorkspaceProps {
  projectUuid: string
  routeDefinitionUuid?: string
}

type WorkspaceTab = 'definition' | 'draft' | 'versions'

const executableTypes = new Set<DefinitionType>(['MAPPING', 'PACKAGE', 'PROCEDURE', 'LOAD_PLAN'])

const fallbackTypes: DefinitionTypeDescriptor[] = DEFINITION_TYPES.map((code) => ({
  code,
  label: code,
  category: code === 'LOAD_PLAN' ? 'ORKESTRASYON' : 'TASARIM',
  folderRequired: ['MAPPING', 'REUSABLE_MAPPING', 'PACKAGE', 'PROCEDURE'].includes(code),
  globalAllowed: ['REUSABLE_MAPPING', 'VARIABLE', 'SEQUENCE', 'KNOWLEDGE_MODULE'].includes(code),
  requiredContentFields: [],
}))

function errorMessage(error: unknown, fallback: string) {
  if (error instanceof ApiProblem) return error.message
  if (error instanceof Error) return error.message
  return fallback
}

export function DefinitionsWorkspace({ projectUuid, routeDefinitionUuid }: DefinitionsWorkspaceProps) {
  const { language, t } = useDefinitionsI18n()
  const navigate = useNavigate()
  const { setPendingChanges } = usePendingChanges()
  const { can } = useProjectAccess()
  const canWrite = can('TANIM_DUZENLE')
  const canValidate = can('TANIM_DOGRULA')
  const canPublish = can('CALISTIRILABILIR_SURUM_OLUSTUR')
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedDefinitionUuid = routeDefinitionUuid ?? searchParams.get('definition')
  const initialSelection = useRef({ projectUuid, uuid: requestedDefinitionUuid })
  if (initialSelection.current.projectUuid !== projectUuid) {
    initialSelection.current = { projectUuid, uuid: requestedDefinitionUuid }
  }
  const definitionRequest = useRef(0)
  const [definitions, setDefinitions] = useState<Definition[]>([])
  const [folders, setFolders] = useState<Folder[]>([])
  const [types, setTypes] = useState<DefinitionTypeDescriptor[]>(fallbackTypes)
  const [selectedUuid, setSelectedUuid] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(false)
  const [createDefinitionType, setCreateDefinitionType] = useState<DefinitionType | null>(null)
  const [createDefinitionFolderUuid, setCreateDefinitionFolderUuid] = useState<string | null>(null)
  const [folderCreateContext, setFolderCreateContext] = useState<{ parentUuid: string | null } | null>(null)
  const [showMoveDefinition, setShowMoveDefinition] = useState(false)
  const [pendingDefinitionUuid, setPendingDefinitionUuid] = useState<string | null>(null)
  const [folderToMove, setFolderToMove] = useState<Folder | null>(null)
  const [creating, setCreating] = useState(false)
  const [creatingFolder, setCreatingFolder] = useState(false)
  const [movingDefinition, setMovingDefinition] = useState(false)
  const [movingFolder, setMovingFolder] = useState(false)
  const [tab, setTab] = useState<WorkspaceTab>('draft')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [content, setContent] = useState<unknown>({})
  const [schemaVersion, setSchemaVersion] = useState(1)
  const [draftLoading, setDraftLoading] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)
  const [versions, setVersions] = useState<DefinitionVersion[]>([])
  const [versionDescription, setVersionDescription] = useState('')
  const [creatingVersion, setCreatingVersion] = useState(false)
  const [selectedVersionUuid, setSelectedVersionUuid] = useState<string | null>(null)
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  const [compiling, setCompiling] = useState(false)
  const [status, setStatus] = useState<{ tone: 'success' | 'error' | 'info'; text: string } | null>(null)

  useEffect(() => {
    const parentUuid = searchParams.get('createFolder')
    if (parentUuid === null) return
    setFolderCreateContext({ parentUuid: parentUuid || null })
    const nextParams = new URLSearchParams(searchParams)
    nextParams.delete('createFolder')
    setSearchParams(nextParams, { replace: true })
  }, [searchParams, setSearchParams])
  useEffect(() => {
    const requestedType = searchParams.get('createType')
    if (!requestedType) return
    if (DEFINITION_TYPES.includes(requestedType as DefinitionType) && requestedType !== 'REUSABLE_MAPPING') {
      setCreateDefinitionType(requestedType as DefinitionType)
      setCreateDefinitionFolderUuid(searchParams.get('folder') || null)
      setSelectedUuid(null)
      setShowCreate(true)
    }
    const nextParams = new URLSearchParams(searchParams)
    nextParams.delete('createType')
    nextParams.delete('folder')
    setSearchParams(nextParams, { replace: true })
  }, [searchParams, setSearchParams])
  const [capabilities, setCapabilities] = useState<ProjectCapabilities | null>(null)
  const [capabilityError, setCapabilityError] = useState(false)
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [logicalSchemas, setLogicalSchemas] = useState<LogicalSchema[]>([])
  const [environmentLoadError, setEnvironmentLoadError] = useState(false)
  const saveDraftAction = useRef<() => Promise<boolean>>(async () => true)

  const selectedDefinition = definitions.find((definition) => definition.uuid === selectedUuid) ?? null
  useDocumentTab(selectedDefinition ? { path: `/project/objects/definitions/${encodeURIComponent(selectedDefinition.uuid)}`, title: selectedDefinition.name, subtitle: selectedDefinition.code, kind: selectedDefinition.type } : null)
  const procedureTechnologies = (procedure: { tasks: Array<{ connectionRole: string; logicalSchemaUuid?: string }> }) => {
    const typeOf = (uuid?: string) => logicalSchemas.find((item) => item.uuid === uuid)?.databaseType ?? undefined
    const pick = (role: string) => procedure.tasks.filter((task) => task.connectionRole === role).map((task) => typeOf(task.logicalSchemaUuid)).find((value): value is string => Boolean(value))
    return { source: pick('SOURCE') ?? 'ORACLE', target: pick('TARGET') ?? 'ORACLE' }
  }
  const selectedVersion = versions.find((version) => version.uuid === selectedVersionUuid) ?? null

  const typeLabel = useCallback(
    (type: DefinitionType) => t(definitionTypeKey[type]),
    [t],
  )

  const loadWorkspace = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      const [nextDefinitions, nextFolders, nextTypes] = await Promise.all([
        definitionsApi.listDefinitions(projectUuid),
        definitionsApi.listFolders(projectUuid),
        definitionsApi.listTypes().catch(() => fallbackTypes),
      ])
      setDefinitions(nextDefinitions)
      setFolders(nextFolders)
      setTypes(nextTypes)
      const requestedUuid = initialSelection.current.uuid
      initialSelection.current.uuid = null
      setSelectedUuid((current) =>
        current && nextDefinitions.some((definition) => definition.uuid === current)
          ? current
          : requestedUuid && nextDefinitions.some((definition) => definition.uuid === requestedUuid) ? requestedUuid : null,
      )
    } catch (error) {
      setLoadError(errorMessage(error, t('loadError')))
    } finally {
      setLoading(false)
    }
  }, [projectUuid, t])

  useEffect(() => {
    void loadWorkspace()
  }, [loadWorkspace])

  useEffect(() => {
    const requestedUuid = routeDefinitionUuid ?? searchParams.get('definition')
    if (!requestedUuid || requestedUuid === selectedUuid || dirty
        || !definitions.some((definition) => definition.uuid === requestedUuid)) return
    definitionRequest.current += 1
    setSelectedUuid(requestedUuid)
    setTab('draft')
  }, [definitions, dirty, routeDefinitionUuid, searchParams, selectedUuid])

  useEffect(() => {
    let active = true
    setCapabilities(null); setCapabilityError(false)
    void executionApi.getCapabilities(projectUuid).then((value) => { if (active) setCapabilities(value) }).catch(() => { if (active) setCapabilityError(true) })
    return () => { active = false }
  }, [projectUuid])

  useEffect(() => {
    let active = true
    setEnvironmentLoadError(false)
    void topologyApi.listLogicalSchemas(projectUuid).then((items) => { if (active) setLogicalSchemas(items) }).catch(() => undefined)
    void topologyApi.listEnvironments(projectUuid).then((items) => { if (active) setEnvironments(items.filter((item) => item.status === 'ETKIN' || item.status === 'AKTIF')) }).catch(() => { if (active) { setEnvironments([]); setEnvironmentLoadError(true) } })
    return () => { active = false }
  }, [projectUuid])

  const loadDefinition = useCallback(async () => {
    if (!selectedDefinition) return
    const requestNumber = ++definitionRequest.current
    setDraftLoading(true)
    setStatus(null)
    setVersions([])
    setScenarios([])
    setSelectedVersionUuid(null)
    try {
      const [nextDraft, nextVersions] = await Promise.all([
        definitionsApi.getDraft(projectUuid, selectedDefinition.uuid),
        definitionsApi.listVersions(projectUuid, selectedDefinition.uuid),
      ])
      if (definitionRequest.current !== requestNumber) return
      setDraft(nextDraft)
      setSchemaVersion(selectedDefinition.type === 'MAPPING' ? 4 : nextDraft?.schemaVersion ?? (selectedDefinition.type === 'PROCEDURE' ? 2 : 1))
      const loadedContent = nextDraft?.content ?? createDefaultContent(selectedDefinition.type)
      setContent(selectedDefinition.type === 'MAPPING' ? editMapping(migrateLegacyMapping(loadedContent)) : loadedContent)
      setDirty(false)
      setVersions(nextVersions)
      setSelectedVersionUuid(nextVersions[0]?.uuid ?? null)
    } catch (error) {
      if (definitionRequest.current === requestNumber) setStatus({ tone: 'error', text: errorMessage(error, t('requestError')) })
    } finally {
      if (definitionRequest.current === requestNumber) setDraftLoading(false)
    }
  }, [projectUuid, selectedDefinition, t])

  useEffect(() => {
    void loadDefinition()
  }, [loadDefinition])

  useEffect(() => {
    if (!selectedDefinition || !selectedVersionUuid) {
      setScenarios([])
      return
    }
    let active = true
    void definitionsApi
      .listScenarios(projectUuid, selectedDefinition.uuid, selectedVersionUuid)
      .then((rows) => active && setScenarios(rows))
      .catch(() => active && setScenarios([]))
    return () => {
      active = false
    }
  }, [projectUuid, selectedDefinition, selectedVersionUuid])

  async function saveDraft(): Promise<boolean> {
    if (!selectedDefinition || saving) return false
    setSaving(true)
    setStatus(null)
    try {
      const saved = await definitionsApi.saveDraft(
        projectUuid,
        selectedDefinition.uuid,
        draft?.version ?? 0,
        selectedDefinition.type === 'MAPPING' ? 4 : selectedDefinition.type === 'KNOWLEDGE_MODULE' && content && typeof content === 'object' && 'language' in content && ['AKIS_KM/1', 'AKIS_KM/2', 'AKIS_KM/3'].includes(String(content.language)) ? 2 : schemaVersion,
        selectedDefinition.type === 'MAPPING' && isMappingContent(content) ? storeMapping(content) : content,
      )
      setDraft(saved)
      setContent(selectedDefinition.type === 'MAPPING' ? editMapping(migrateLegacyMapping(saved.content)) : saved.content)
      setDirty(false)
      notifyFeedback(t('saved'))
      return true
    } catch (error) {
      const isConflict = error instanceof ApiProblem && error.status === 409
      setStatus({ tone: 'error', text: isConflict ? t('conflict') : errorMessage(error, t('requestError')) })
      return false
    } finally {
      setSaving(false)
    }
  }
  saveDraftAction.current = saveDraft

  useEffect(() => {
    setPendingChanges(dirty && canWrite ? { save: () => saveDraftAction.current() } : null)
    return () => setPendingChanges(null)
  }, [canWrite, dirty, setPendingChanges])

  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  function handleWorkspaceKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 's') {
      event.preventDefault()
      void saveDraft()
    }
  }

  async function createVersion(event: FormEvent) {
    event.preventDefault()
    if (!selectedDefinition || !draft || dirty || creatingVersion) return
    setCreatingVersion(true)
    setStatus(null)
    try {
      const created = await definitionsApi.createVersion(
        projectUuid,
        selectedDefinition.uuid,
        draft.version,
        versionDescription,
      )
      setVersions((current) => [created, ...current])
      setSelectedVersionUuid(created.uuid)
      setVersionDescription('')
      setStatus({ tone: 'success', text: `${t('version')} ${created.versionNumber}` })
    } catch (error) {
      const isConflict = error instanceof ApiProblem && error.status === 409
      setStatus({ tone: 'error', text: isConflict ? t('conflict') : errorMessage(error, t('requestError')) })
    } finally {
      setCreatingVersion(false)
    }
  }

  async function compileScenario() {
    if (!selectedDefinition || !selectedVersion || compiling) return
    setCompiling(true)
    setStatus(null)
    try {
      const scenario = await definitionsApi.compileScenario(
        projectUuid,
        selectedDefinition.uuid,
        selectedVersion.uuid,
      )
      setScenarios((current) => [scenario, ...current.filter((item) => item.uuid !== scenario.uuid)])
      setStatus({ tone: 'success', text: t('scenarioVersion', { version: scenario.scenarioVersion }) })
    } catch (error) {
      setStatus({ tone: 'error', text: errorMessage(error, t('requestError')) })
    } finally {
      setCompiling(false)
    }
  }

  function updateContent(next: unknown) {
    setContent(next)
    setDirty(true)
    setStatus(null)
  }

  function applyDefinitionSelection(uuid: string) {
    definitionRequest.current += 1
    setShowCreate(false)
    setCreateDefinitionType(null)
    setCreateDefinitionFolderUuid(null)
    setSelectedUuid(uuid)
    setTab('draft')
    void navigate(`/project/objects/definitions/${encodeURIComponent(uuid)}`, { replace: true })
  }

  function navigateFromExplorer(path: string) {
    const uuid = path.match(/\/definitions\/([^/?]+)/)?.[1]
    if (uuid) {
      if (dirty) setPendingDefinitionUuid(decodeURIComponent(uuid))
      else applyDefinitionSelection(decodeURIComponent(uuid))
    } else void navigate(path)
  }


  return (
    <div className="definitions-workspace" onKeyDown={handleWorkspaceKeyDown}>
      <h1 className="sr-only">{t('title')}</h1>
      {capabilityError && <div className="definition-notice definition-notice--info" role="status"><AlertCircle size={16} aria-hidden="true" /><span>{t('capabilityUnavailable')}</span></div>}
      {environmentLoadError && <div className="definition-notice definition-notice--error" role="alert"><AlertCircle size={16} aria-hidden="true" /><span>{t('environmentLoadError')}</span></div>}

      <div className="definitions-shell definitions-shell--workbench">
        <section className="definition-workbench" aria-label={t('details')}>
          {loading && <div className="definition-state"><LoaderCircle className="spin" aria-hidden="true" /> {t('loading')}</div>}
          {loadError && <div className="definition-state definition-state--error"><AlertCircle aria-hidden="true" /><p>{loadError}</p><AntActionButton tone="secondary" type="button" onClick={() => void loadWorkspace()}>{t('retry')}</AntActionButton></div>}
          {status && (
            <div className={`definition-notice definition-notice--${status.tone}`} role={status.tone === 'error' ? 'alert' : 'status'}>
              {status.tone === 'success' ? <Check size={16} aria-hidden="true" /> : <AlertCircle size={16} aria-hidden="true" />}
              <span>{status.text}</span>
              {status.tone === 'error' && selectedDefinition && draft ? (
                <AntActionButton tone="ghost" type="button" onClick={() => void loadDefinition()}>{t('reloadDraft')}</AntActionButton>
              ) : null}
            </div>
          )}
          {showCreate && canWrite ? (
            <CreateDefinitionEditor
              folders={folders}
              types={types.filter((type) => type.code !== 'REUSABLE_MAPPING')}
              initialType={createDefinitionType}
              initialFolderUuid={createDefinitionFolderUuid}
              creating={creating}
              close={() => { setShowCreate(false); setCreateDefinitionType(null); setCreateDefinitionFolderUuid(null) }}
              onCreate={async (input, initialContent) => {
                setCreating(true)
                setStatus(null)
                try {
                  const created = await definitionsApi.createDefinition(projectUuid, input)
                  await definitionsApi.saveDraft(projectUuid, created.uuid, 0, initialSchemaVersion(input.type, initialContent), input.type === 'MAPPING' && isMappingContent(initialContent) ? storeMapping(initialContent) : initialContent)
                  setDefinitions((current) => [created, ...current])
                  window.dispatchEvent(new Event('akis:definitions-changed'))
                  setSelectedUuid(created.uuid)
                  setShowCreate(false)
                  setCreateDefinitionType(null)
                  setCreateDefinitionFolderUuid(null)
                  setTab('draft')
                  notifyProjectTreeChanged()
                  navigate(`/project/objects/definitions/${encodeURIComponent(created.uuid)}`, { replace: true })
                } catch (error) {
                  setStatus({ tone: 'error', text: errorMessage(error, t('requestError')) })
                } finally {
                  setCreating(false)
                }
              }}
            />
          ) : !selectedDefinition ? (
            <DefinitionCatalog definitions={definitions} folders={folders} typeLabel={typeLabel} canWrite={canWrite} onOpen={(uuid) => navigate(`/project/objects/definitions/${encodeURIComponent(uuid)}`)} onCreate={() => { setCreateDefinitionType(null); setCreateDefinitionFolderUuid(null); setShowCreate(true) }} />
          ) : (
            <>

              <div className="definition-tab-layout"><div className="definition-tabs definition-tabs--horizontal" role="tablist" aria-label={t('details')} onKeyDown={(event) => {
                if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
                const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
                const current = buttons.indexOf(document.activeElement as HTMLButtonElement)
                const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (current + (event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
                event.preventDefault(); buttons[next]?.focus(); buttons[next]?.click()
              }}>
                <AntActionButton tone="ghost" id="definition-tab-definition" type="button" role="tab" className="definition-tab definition-tab--definition" tabIndex={tab === 'definition' ? 0 : -1} aria-selected={tab === 'definition'} aria-controls="definition-panel-definition" onClick={() => setTab('definition')}>
                  <SlidersHorizontal size={16} aria-hidden="true" /> {t('definitionTab')}
                </AntActionButton>
                <AntActionButton tone="ghost" id="definition-tab-draft" type="button" role="tab" className="definition-tab definition-tab--draft" tabIndex={tab === 'draft' ? 0 : -1} aria-selected={tab === 'draft'} aria-controls="definition-panel-draft" onClick={() => setTab('draft')}>
                  {selectedDefinition.type === 'PROCEDURE' ? <ListOrdered size={16} aria-hidden="true" /> : selectedDefinition.type === 'PACKAGE' ? <Workflow size={16} aria-hidden="true" /> : <FileCode2 size={16} aria-hidden="true" />} {selectedDefinition.type === 'PROCEDURE' ? t('tasksTab') : selectedDefinition.type === 'MAPPING' || selectedDefinition.type === 'REUSABLE_MAPPING' ? t('designTab') : selectedDefinition.type === 'PACKAGE' ? t('diagramTab') : t('contentTab')}
                  {dirty && <span className="definition-dirty-dot" aria-label={t('unsaved')} />}
                </AntActionButton>
                <AntActionButton tone="ghost" id="definition-tab-versions" type="button" role="tab" className="definition-tab definition-tab--versions" tabIndex={tab === 'versions' ? 0 : -1} aria-selected={tab === 'versions'} aria-controls="definition-panel-versions" onClick={() => setTab('versions')}>
                  <GitBranch size={16} aria-hidden="true" /> {t('versions')} <span className="definition-count">{versions.length}</span>
                </AntActionButton>
                <span className="definition-tabs-identity" title={selectedDefinition.description ?? undefined}><span className="definition-type-chip"><DefinitionTypeIcon type={selectedDefinition.type} size={12} />{typeLabel(selectedDefinition.type)}</span><code>{selectedDefinition.code}</code></span>
                {(tab === 'draft' || tab === 'definition') && !draftLoading && canWrite && (
                  <div className="definition-document-save">
                    {dirty && <span className="definition-unsaved-state">{t('unsaved')}</span>}
                    <AntActionButton tone="primary" type="button" disabled={saving || !dirty} onClick={() => void saveDraft()}>
                      {saving ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Save size={16} aria-hidden="true" />}
                      {saving ? t('saving') : t('saveDraft')}
                    </AntActionButton>
                  </div>
                )}
              </div>
              <div className="definition-tab-content">
              {tab === 'definition' ? (
                <section id="definition-panel-definition" className="definition-editor-panel" role="tabpanel" aria-labelledby="definition-tab-definition">
                  <DefinitionPropertiesPanel key={`${selectedDefinition.uuid}:${selectedDefinition.version}`} projectUuid={projectUuid} definition={selectedDefinition} canWrite={canWrite}
                    technology={selectedDefinition.type === 'PROCEDURE' && isProcedureContent(content) ? (content.technology ?? procedureTechnologies(content)) : undefined}
                    onTechnologyChange={selectedDefinition.type === 'PROCEDURE' && isProcedureContent(content) ? (technology) => updateContent({ ...content, technology }) : undefined}
                    onUpdated={(updated) => { setDefinitions((current) => current.map((definition) => definition.uuid === updated.uuid ? updated : definition)); window.dispatchEvent(new Event('akis:definitions-changed')); notifyProjectTreeChanged() }} />
                </section>
              ) : draftLoading ? (
                <div className="definition-state"><LoaderCircle className="spin" aria-hidden="true" /> {t('loading')}</div>
              ) : tab === 'draft' ? (
                <section id="definition-panel-draft" className="definition-editor-panel" role="tabpanel" aria-labelledby="definition-tab-draft">
                  <fieldset className="definition-readonly-gate" disabled={!canWrite}>{selectedDefinition.type === 'MAPPING' && isMappingContent(content) ? (
                    <MappingGrid projectUuid={projectUuid} value={content} onChange={updateContent} schemaVersion={schemaVersion}
                      onUpgrade={() => { setSchemaVersion(4); setDirty(true); notifyFeedback(language === 'tr' ? 'Taslak yeni arayüz sözleşmesine dönüştürüldü. Mevcut yayınlar değişmez.' : 'Draft converted to the new interface contract. Existing publications remain unchanged.') }} />
                  ) : selectedDefinition.type === 'PROCEDURE' && isProcedureContent(content) ? (
                    <ProcedureEditor projectUuid={projectUuid} definition={selectedDefinition} value={content} onChange={updateContent} limits={capabilities?.procedure} />
                  ) : selectedDefinition.type === 'PACKAGE' ? (
                    <PackageEditor projectUuid={projectUuid} definitionUuid={selectedDefinition.uuid} value={content} onChange={updateContent} onOpenDefinition={(uuid) => navigateFromExplorer(`/project/objects/definitions/${encodeURIComponent(uuid)}`)} />
                  ) : !['MAPPING', 'PROCEDURE', 'REUSABLE_MAPPING'].includes(selectedDefinition.type) ? (
                    <StructuredDraftEditor projectUuid={projectUuid} definitionUuid={selectedDefinition.uuid} type={selectedDefinition.type} value={content} onChange={updateContent} />
                  ) : (
                    <div className="definition-state definition-state--error"><AlertCircle aria-hidden="true" /><p>{t('unsupportedDraftShape')}</p>{canWrite && <AntActionButton tone="secondary" type="button" onClick={() => updateContent(createDefaultContent(selectedDefinition.type))}>{t('resetStructuredDraft')}</AntActionButton>}</div>
                  )}</fieldset>
                  {selectedDefinition.type === 'VARIABLE' && <VariableHistory key={selectedDefinition.uuid} projectUuid={projectUuid} definitionUuid={selectedDefinition.uuid} />}
                </section>
              ) : tab === 'versions' ? (
                <VersionsPanel
                  projectUuid={projectUuid}
                  definitionName={selectedDefinition.name}
                  definitionType={selectedDefinition.type}
                  versions={versions}
                  selectedVersionUuid={selectedVersionUuid}
                  setSelectedVersionUuid={setSelectedVersionUuid}
                  draft={draft}
                  dirty={dirty}
                  versionDescription={versionDescription}
                  setVersionDescription={setVersionDescription}
                  creatingVersion={creatingVersion}
                  createVersion={createVersion}
                  scenarios={scenarios}
                  executable={executableTypes.has(selectedDefinition.type)}
                  compiling={compiling}
                  compileScenario={compileScenario}
                  environments={environments}
                  onError={(text) => setStatus({ tone: 'error', text })}
                  canWrite={canWrite}
                  canValidate={canValidate}
                  canPublish={canPublish}
                />
              ) : null}
              </div></div>
            </>
          )}
        </section>
      </div>

      {folderCreateContext && canWrite && (
        <CreateFolderDialog
          parentUuid={folderCreateContext.parentUuid}
          parentName={folders.find((folder) => folder.uuid === folderCreateContext.parentUuid)?.name ?? null}
          creating={creatingFolder}
          close={() => setFolderCreateContext(null)}
          onCreate={async (input) => {
            setCreatingFolder(true)
            try {
              const created = await definitionsApi.createFolder(projectUuid, input)
              setFolders((current) => [...current, created])
              window.dispatchEvent(new Event('akis:definitions-changed'))
              notifyProjectTreeChanged()
              setFolderCreateContext(null)
              setStatus({ tone: 'success', text: t('folderCreated') })
            } catch (error) {
              setStatus({ tone: 'error', text: errorMessage(error, t('requestError')) })
            } finally {
              setCreatingFolder(false)
            }
          }}
        />
      )}
      {showMoveDefinition && selectedDefinition && (
        <MoveDefinitionDialog
          definition={selectedDefinition}
          folders={folders}
          folderRequired={types.find((type) => type.code === selectedDefinition.type)?.folderRequired ?? false}
          moving={movingDefinition}
          close={() => setShowMoveDefinition(false)}
          onMove={async (input) => {
            setMovingDefinition(true)
            try {
              const moved = await definitionsApi.moveDefinition(projectUuid, selectedDefinition.uuid, input)
              setDefinitions((current) => current.map((definition) => definition.uuid === moved.uuid ? moved : definition))
              notifyProjectTreeChanged()
              setShowMoveDefinition(false)
              setStatus({ tone: 'success', text: t('definitionMoved') })
            } catch (error) {
              setStatus({ tone: 'error', text: errorMessage(error, t('requestError')) })
            } finally {
              setMovingDefinition(false)
            }
          }}
        />
      )}
      {folderToMove && (
        <MoveFolderDialog
          folder={folderToMove}
          folders={folders}
          moving={movingFolder}
          close={() => setFolderToMove(null)}
          onMove={async (input) => {
            setMovingFolder(true)
            try {
              const moved = await definitionsApi.moveFolder(projectUuid, folderToMove.uuid, input)
              setFolders((current) => current.map((folder) => folder.uuid === moved.uuid ? moved : folder))
              notifyProjectTreeChanged()
              setFolderToMove(null)
              setStatus({ tone: 'success', text: t('folderMoved') })
            } catch (error) {
              setStatus({ tone: 'error', text: errorMessage(error, t('requestError')) })
            } finally {
              setMovingFolder(false)
            }
          }}
        />
      )}
      <Dialog open={pendingDefinitionUuid !== null} title={t('changeObjectTitle')} eyebrow={t('unsaved')} closeLabel={t('cancel')} busy={saving} onClose={() => setPendingDefinitionUuid(null)} className="definition-dialog definition-dialog--compact">
        <p>{t('changeObjectDescription')}</p>
        <footer className="dialog-actions"><AntActionButton tone="secondary" type="button" onClick={() => setPendingDefinitionUuid(null)}>{t('stay')}</AntActionButton><AntActionButton tone="secondary" type="button" onClick={() => { const uuid = pendingDefinitionUuid; setPendingDefinitionUuid(null); setDirty(false); if (uuid) applyDefinitionSelection(uuid) }}>{t('discardAndContinue')}</AntActionButton><AntActionButton tone="primary" type="button" onClick={async () => { const uuid = pendingDefinitionUuid; if (await saveDraft()) { setPendingDefinitionUuid(null); if (uuid) applyDefinitionSelection(uuid) } }}>{t('saveAndContinue')}</AntActionButton></footer>
      </Dialog>
    </div>
  )
}

interface CreateFolderDialogProps {
  parentUuid: string | null
  parentName: string | null
  creating: boolean
  close: () => void
  onCreate: (input: NewFolderInput) => Promise<void>
}

function CreateFolderDialog({ parentUuid, parentName, creating, close, onCreate }: CreateFolderDialogProps) {
  const { t } = useDefinitionsI18n()
  const [input, setInput] = useState<NewFolderInput>({ parentUuid, code: '', name: '', description: '' })
  async function submit(event: FormEvent) {
    event.preventDefault()
    await onCreate(input)
  }
  return (
    <Dialog open title={parentName ? t('newSubfolderNamed', { name: parentName }) : t('newFolder')} closeLabel={t('close')} onClose={close} busy={creating} className="definition-dialog definition-dialog--compact" backdropClassName="definition-dialog-backdrop">
        <form onSubmit={submit}>
          <div className="definition-form-grid">
            <label><span>{t('code')}</span><AntInput autoFocus required pattern="[A-Z][A-Z0-9_]{0,99}" placeholder="FINANCE" value={input.code} onChange={(event) => setInput({ ...input, code: event.target.value.toLocaleUpperCase('en-US').replace(/[^A-Z0-9_]/g, '') })} /></label>
            <label><span>{t('name')}</span><AntInput required value={input.name} onChange={(event) => setInput({ ...input, name: event.target.value })} /></label>
            <label className="definition-form-grid--wide"><span>{t('description')}</span><AntInput.TextArea rows={3} value={input.description} onChange={(event) => setInput({ ...input, description: event.target.value })} /></label>
          </div>
          <footer><AntActionButton tone="secondary" type="button" onClick={close}>{t('cancel')}</AntActionButton><AntActionButton tone="primary" type="submit" disabled={creating}>{creating ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}{creating ? t('creatingFolder') : t('createFolder')}</AntActionButton></footer>
        </form>
    </Dialog>
  )
}

interface MoveDefinitionDialogProps {
  definition: Definition
  folders: Folder[]
  folderRequired: boolean
  moving: boolean
  close: () => void
  onMove: (input: MoveDefinitionInput) => Promise<void>
}

function MoveDefinitionDialog({ definition, folders, folderRequired, moving, close, onMove }: MoveDefinitionDialogProps) {
  const { t } = useDefinitionsI18n()
  const [folderUuid, setFolderUuid] = useState<string | null>(definition.folderUuid)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (folderRequired && !folderUuid) return
    await onMove({ folderUuid, expectedVersion: definition.version })
  }
  return (
    <Dialog open title={t('moveDefinition')} eyebrow={t('designControl')} closeLabel={t('close')} onClose={close} busy={moving} className="definition-dialog definition-dialog--compact" backdropClassName="definition-dialog-backdrop">
        <form onSubmit={submit}>
          <p className="definition-dialog-help">{t('moveDefinitionHelp')}</p>
          <label><span>{t('folder')}</span><FormSelect autoFocus required={folderRequired} value={folderUuid ?? ''} onChange={(event) => setFolderUuid(event.target.value || null)}><option value="" disabled={folderRequired}>{t('unfiled')}</option>{folders.filter((folder) => folder.status === 'AKTIF').map((folder) => <option key={folder.uuid} value={folder.uuid}>{folder.name} · {folder.code}</option>)}</FormSelect>{folderRequired && !folderUuid ? <small className="definition-field-error">{t('folderRequired')}</small> : null}</label>
          <footer><AntActionButton tone="secondary" type="button" onClick={close}>{t('cancel')}</AntActionButton><AntActionButton tone="primary" type="submit" disabled={moving || folderUuid === definition.folderUuid || (folderRequired && !folderUuid)}>{moving ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <FolderInput size={16} aria-hidden="true" />}{moving ? t('movingDefinition') : t('move')}</AntActionButton></footer>
        </form>
    </Dialog>
  )
}

interface MoveFolderDialogProps {
  folder: Folder
  folders: Folder[]
  moving: boolean
  close: () => void
  onMove: (input: MoveFolderInput) => Promise<void>
}

function MoveFolderDialog({ folder, folders, moving, close, onMove }: MoveFolderDialogProps) {
  const { t } = useDefinitionsI18n()
  const [parentUuid, setParentUuid] = useState<string | null>(folder.parentUuid)
  const unavailable = useMemo(() => {
    const result = new Set([folder.uuid])
    let changed = true
    while (changed) {
      changed = false
      for (const candidate of folders) {
        if (candidate.parentUuid && result.has(candidate.parentUuid) && !result.has(candidate.uuid)) {
          result.add(candidate.uuid)
          changed = true
        }
      }
    }
    return result
  }, [folder.uuid, folders])
  async function submit(event: FormEvent) {
    event.preventDefault()
    await onMove({ parentUuid, expectedVersion: folder.version })
  }
  return (
    <Dialog open title={t('moveFolder')} eyebrow={t('designControl')} closeLabel={t('close')} onClose={close} busy={moving} className="definition-dialog definition-dialog--compact" backdropClassName="definition-dialog-backdrop">
        <form onSubmit={submit}>
          <p className="definition-dialog-help">{t('moveFolderHelp')}</p>
          <label><span>{t('parentFolder')}</span><FormSelect autoFocus value={parentUuid ?? ''} onChange={(event) => setParentUuid(event.target.value || null)}><option value="">{t('rootFolder')}</option>{folders.filter((candidate) => candidate.status === 'AKTIF' && !unavailable.has(candidate.uuid)).map((candidate) => <option key={candidate.uuid} value={candidate.uuid}>{candidate.name} · {candidate.code}</option>)}</FormSelect></label>
          <footer><AntActionButton tone="secondary" type="button" onClick={close}>{t('cancel')}</AntActionButton><AntActionButton tone="primary" type="submit" disabled={moving || parentUuid === folder.parentUuid}>{moving ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <FolderInput size={16} aria-hidden="true" />}{moving ? t('movingFolder') : t('move')}</AntActionButton></footer>
        </form>
    </Dialog>
  )
}

interface VersionsPanelProps {
  projectUuid: string
  definitionName: string
  definitionType: DefinitionType
  versions: DefinitionVersion[]
  selectedVersionUuid: string | null
  setSelectedVersionUuid: (uuid: string) => void
  draft: Draft | null
  dirty: boolean
  versionDescription: string
  setVersionDescription: (value: string) => void
  creatingVersion: boolean
  createVersion: (event: FormEvent) => void
  scenarios: Scenario[]
  executable: boolean
  compiling: boolean
  compileScenario: () => void
  environments: Environment[]
  onError: (message: string) => void
  canWrite: boolean
  canValidate: boolean
  canPublish: boolean
}

function VersionsPanel(props: VersionsPanelProps) {
  const { language, t } = useDefinitionsI18n()
  const formatter = useMemo(() => new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }), [language])
  const selected = props.versions.find((version) => version.uuid === props.selectedVersionUuid)
  // Runnable versions target the project's default environment (ODI: the context of the object); no per-scenario choice.
  const environment = props.environments.find((item) => item.defaultEnvironment) ?? props.environments[0]
  const environmentUuid = environment?.uuid ?? ''
  const [preparingScenarioUuid, setPreparingScenarioUuid] = useState('')
  const [prepared, setPrepared] = useState<Record<string, Publication>>({})
  const [kmPreviews, setKmPreviews] = useState<Record<string, string>>({})
  const [simulated, setSimulated] = useState<Record<string, boolean>>({})
  const stagedMapping = props.definitionType === 'MAPPING' && selected != null && [3, 4].includes(selected.schemaVersion)
  const simulationRequired = stagedMapping || props.definitionType === 'PROCEDURE' || props.definitionType === 'PACKAGE'
  async function prepare(scenario: Scenario) {
    if (!environmentUuid) return
    setPreparingScenarioUuid(scenario.uuid)
    try {
      const publication = await operationsApi.createPublication(props.projectUuid, scenario.uuid, environmentUuid, kmPreviews[`${scenario.uuid}:${environmentUuid}`])
      setPrepared((current) => ({ ...current, [scenario.uuid]: publication }))
    } catch (error) { props.onError(errorMessage(error, t('requestError'))) }
    finally { setPreparingScenarioUuid('') }
  }
  const environmentChip = <span className="definition-runnable-environment" title={t('environmentOfObject')}><Layers size={14} aria-hidden="true" />{environment ? <><strong>{environment.name}</strong><code>{environment.code}</code></> : <em>{t('noDefaultEnvironment')}</em>}</span>
  const key = (scenario: Scenario) => `${scenario.uuid}:${environmentUuid}`
  return (
    <section id="definition-panel-versions" className="definition-version-layout definition-version-layout--stacked" role="tabpanel" aria-labelledby="definition-tab-versions">
      <div className="definition-version-toolbar">
        {props.canWrite ? <form className="definition-version-form" onSubmit={props.createVersion}>
          <label className="definition-version-note">
            <span className="sr-only">{t('versionDescription')}</span>
            <AntInput value={props.versionDescription} placeholder={t('versionDescriptionPlaceholder')} onChange={(event) => props.setVersionDescription(event.target.value)} />
          </label>
          <AntActionButton tone="primary" type="submit" disabled={!props.draft || props.dirty || props.creatingVersion} title={props.dirty ? t('unsaved') : undefined}>
            {props.creatingVersion ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}
            {props.creatingVersion ? t('creatingVersion') : t('createVersion')}
          </AntActionButton>
        </form> : <span />}
        {environmentChip}
      </div>
      {props.versions.length === 0 ? <p className="definition-state">{t('noVersions')}</p> : (
        <DataGrid viewControls={false} className="definition-version-table">
          <thead><tr><th scope="col">{t('version')}</th><th scope="col">{t('versionDescription')}</th><th scope="col">{t('createdAt')}</th><th scope="col">{t('contentHash')}</th><th scope="col"><span className="sr-only">{t('details')}</span></th></tr></thead>
          <tbody>{props.versions.map((version) => {
            const isSelected = version.uuid === props.selectedVersionUuid
            return <tr key={version.uuid} className={`definition-version-row${isSelected ? ' is-selected' : ''}`} aria-selected={isSelected} onClick={() => props.setSelectedVersionUuid(version.uuid)}>
              <th scope="row"><span className="definition-version-number">v{version.versionNumber}</span></th>
              <td><strong>{version.description || `${t('version')} ${version.versionNumber}`}</strong></td>
              <td><time dateTime={version.createdAt}>{formatter.format(new Date(version.createdAt))}</time></td>
              <td><code>{version.contentHash.slice(0, 12)}</code></td>
              <td className="definition-version-chevron"><ChevronRight size={16} aria-hidden="true" /></td>
            </tr>
          })}</tbody>
        </DataGrid>
      )}
      {selected && <div className="definition-version-detail">
        <div className="definition-panel-heading">
          <div><h3><span className="procedure-heading-icon procedure-heading-icon--scenarios" aria-hidden="true"><Workflow size={16} /></span>{t('scenarios')} · v{selected.versionNumber}<span className="procedure-heading-count">{props.scenarios.length}</span></h3><p>{!props.executable ? t('notExecutable') : props.scenarios.length === 0 ? t('noScenarios') : `${t('version')} ${selected.versionNumber} · ${selected.contentHash.slice(0, 12)}`}</p></div>
          {props.canValidate && props.executable && (
            <AntActionButton tone={props.scenarios.length === 0 ? 'primary' : 'secondary'} type="button" disabled={props.compiling} onClick={props.compileScenario}>
              {props.compiling ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <GitBranch size={16} aria-hidden="true" />}
              {props.compiling ? t('compiling') : t('compile')}
            </AntActionButton>
          )}
        </div>
        {props.executable && props.scenarios.length > 0 && <div className="definition-scenario-list">
          {props.scenarios.map((scenario) => (
            <article key={scenario.uuid}>
              <header className="definition-scenario-head">
                <span className="definition-version-number definition-version-number--scenario">S{scenario.scenarioVersion}</span>
                <div className="definition-scenario-title"><strong>{t('scenarioVersion', { version: scenario.scenarioVersion })}</strong><small>{t('planVersion', { version: scenario.planVersion })} · <time dateTime={scenario.createdAt}>{formatter.format(new Date(scenario.createdAt))}</time> · <code>{scenario.planHash.slice(0, 12)}</code></small></div>
                {props.canPublish && <div className="definition-runnable-buttons">
                  {stagedMapping && <PreRunReport projectUuid={props.projectUuid} scenarioUuid={scenario.uuid} environmentUuid={environmentUuid} environmentName={environment?.name ?? ''} definitionName={props.definitionName} tr={language === 'tr'} onReady={hash => setKmPreviews(current => ({ ...current, [key(scenario)]: hash }))} />}
                  {props.definitionType === 'PROCEDURE' && isProcedureContent(selected.content) && environmentUuid && <ProcedureSimulationReport projectUuid={props.projectUuid} content={selected.content} environmentUuid={environmentUuid} definitionName={props.definitionName} onReady={() => setSimulated((current) => ({ ...current, [key(scenario)]: true }))} />}
                  {props.definitionType === 'PACKAGE' && environmentUuid && <PackageSimulationReport projectUuid={props.projectUuid} content={selected.content} environmentUuid={environmentUuid} definitionName={props.definitionName} onReady={() => setSimulated((current) => ({ ...current, [key(scenario)]: true }))} />}
                  {prepared[scenario.uuid] ? <Link className="definition-button definition-button--quiet" to={`/project/publications/${prepared[scenario.uuid]!.uuid}`}>{t('reviewRunnableVersion')}</Link> : <AntActionButton tone="primary" type="button" title={simulationRequired && !kmPreviews[key(scenario)] && !simulated[key(scenario)] ? (language === 'tr' ? 'Önce simüle edin' : 'Simulate first') : undefined} disabled={!environmentUuid || preparingScenarioUuid === scenario.uuid || (stagedMapping && !kmPreviews[key(scenario)]) || ((props.definitionType === 'PROCEDURE' || props.definitionType === 'PACKAGE') && !simulated[key(scenario)])} onClick={() => void prepare(scenario)}>{preparingScenarioUuid === scenario.uuid ? t('preparingRunnableVersion') : t('prepareRunnableVersion')}</AntActionButton>}
                </div>}
              </header>
            </article>
          ))}
        </div>}
      </div>}
    </section>
  )
}

interface CreateDefinitionEditorProps {
  folders: Folder[]
  types: DefinitionTypeDescriptor[]
  initialType: DefinitionType | null
  initialFolderUuid: string | null
  creating: boolean
  close: () => void
  onCreate: (input: NewDefinitionInput, content: unknown) => Promise<void>
}

/** Procedures carry their technologies from the first save so the Tasks tab filters logical schemas immediately. */
const withDefaultTechnology = (content: unknown) => isProcedureContent(content) ? { ...content, technology: { source: 'ORACLE', target: 'ORACLE', multiConnection: true, ...content.technology } } : content

function CreateDefinitionEditor({ folders, types, initialType, initialFolderUuid, creating, close, onCreate }: CreateDefinitionEditorProps) {
  const { t } = useDefinitionsI18n()
  const [input, setInput] = useState<NewDefinitionInput>({ folderUuid: initialFolderUuid, type: initialType ?? 'MAPPING', code: '', name: '', description: '' })
  const [initialContent, setInitialContent] = useState<unknown>(() => withDefaultTechnology(createDefaultContent(initialType ?? 'MAPPING')))
  const descriptor = types.find((type) => type.code === input.type)
  const fixedComponentType = initialType !== null && !descriptor?.folderRequired
  // Opened from a folder in the explorer: the object is created right there, so the folder is shown, not chosen.
  const contextFolder = initialFolderUuid ? folders.find((folder) => folder.uuid === initialFolderUuid) ?? null : null
  const folderPath = (folder: Folder | null): string => folder ? [folderPath(folders.find((item) => item.uuid === folder.parentUuid) ?? null), folder.name].filter(Boolean).join(' / ') : ''
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (descriptor?.folderRequired && !input.folderUuid) return
    await onCreate(input, initialContent)
  }
  return (
    <section className="definition-new-editor" aria-label={initialType ? t('newDefinitionNamed', { name: t(definitionTypeKey[initialType]) }) : t('newDefinition')}>
      <form onSubmit={submit}>
        <header className="definition-document-header definition-new-editor-header"><div><div className="definition-document-meta"><span className="definition-type-chip"><DefinitionTypeIcon type={input.type} size={12} />{t(definitionTypeKey[input.type])}</span>{contextFolder && <span className="definition-context-folder"><ProjectFolderIcon size={13} />{folderPath(contextFolder)}</span>}<span>{t('newDefinition')}</span></div><h2>{input.name || t('newDefinitionNamed', { name: t(definitionTypeKey[input.type]) })}</h2></div><div className="definition-editor-actions"><AntActionButton tone="secondary" type="button" onClick={close}>{t('cancel')}</AntActionButton><AntActionButton tone="primary" type="submit" disabled={creating || !input.code || !input.name || Boolean(descriptor?.folderRequired && !input.folderUuid)}>{creating ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Save size={16} aria-hidden="true" />}{creating ? t('creating') : t('saveDraft')}</AntActionButton></div></header>
        <fieldset disabled={creating} className="definition-new-editor-fields">
          <div className="definition-form-grid">
            {!initialType && <label><span>{t('type')}</span><FormSelect value={input.type} onChange={(event) => { const type = event.target.value as DefinitionType; setInput({ ...input, type, folderUuid: null }); setInitialContent(withDefaultTechnology(createDefaultContent(type))) }}>{types.map((type) => <option key={type.code} value={type.code}>{t(definitionTypeKey[type.code])}</option>)}</FormSelect></label>}
            {!fixedComponentType && !contextFolder && <label><span>{t('folder')}</span><FormSelect required={descriptor?.folderRequired} value={input.folderUuid ?? ''} onChange={(event) => setInput({ ...input, folderUuid: event.target.value || null })}><option value="">{t('noFolder')}</option>{folders.filter((folder) => folder.status === 'AKTIF').map((folder) => <option key={folder.uuid} value={folder.uuid}>{folder.name} · {folder.code}</option>)}</FormSelect>{descriptor?.folderRequired && !input.folderUuid && <small>{t('folderRequired')}</small>}</label>}
            <label><span>{t('code')}</span><AntInput autoFocus required pattern="[A-Z][A-Z0-9_]{0,99}" placeholder="CUSTOMER_LOAD" value={input.code} onChange={(event) => setInput({ ...input, code: event.target.value.toLocaleUpperCase('en-US').replace(/[^A-Z0-9_]/g, '') })} /></label>
            <label><span>{t('name')}</span><AntInput required value={input.name} onChange={(event) => setInput({ ...input, name: event.target.value })} /></label>
            {input.type === 'PROCEDURE' && isProcedureContent(initialContent) && <>
              <label><span>{t('sourceTechnology')}</span><FormSelect value={initialContent.technology?.source ?? 'ORACLE'} onChange={(event) => setInitialContent({ ...initialContent, technology: { ...initialContent.technology, source: event.target.value } })}>{DATABASE_TYPES.map((type) => <option key={type} value={type}>{databaseProviderVisual(type).label}</option>)}</FormSelect></label>
              <label><span>{t('targetTechnology')}</span><FormSelect value={initialContent.technology?.target ?? 'ORACLE'} onChange={(event) => setInitialContent({ ...initialContent, technology: { ...initialContent.technology, target: event.target.value } })}>{DATABASE_TYPES.map((type) => <option key={type} value={type}>{databaseProviderVisual(type).label}</option>)}</FormSelect></label>
            </>}
            <label className="definition-form-grid--wide"><span>{t('description')}</span><AntInput.TextArea rows={3} value={input.description} onChange={(event) => setInput({ ...input, description: event.target.value })} /></label>
          </div>
        </fieldset>
        <p className="definition-new-editor-hint"><AlertCircle size={15} aria-hidden="true" />{t('createThenDesign')}</p>
      </form>
    </section>
  )
}
