import { TabBar } from '../../core/ui/TabBar'
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
  Layers3,
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
import { executionApi } from '../execution/api'
import type { ProjectCapabilities } from '../execution/types'
import { operationsApi } from '../operations/api'
import type { Publication } from '../operations/types'
import { topologyApi, type Environment } from '../topology/api'
import { definitionsApi } from './api'
import { bindingNodes, candidateLabel, unboundNodes } from './bindingCatalog'
import { createDefaultContent, isMappingContent, isProcedureContent } from './defaults'
import { definitionTypeKey, useDefinitionsI18n } from './i18n'
import { MappingGrid } from './MappingGrid'
import { PackageEditor } from './PackageEditor'
import { ProcedureEditor } from './ProcedureEditor'
import { StructuredDraftEditor } from './StructuredDraftEditor'
import { VariableHistory } from './VariableHistory'
import type {
  DataBinding,
  BindingCandidate,
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
import './definitions.css'

const notifyProjectTreeChanged = () => window.dispatchEvent(new Event('akis:definitions-changed'))

interface DefinitionsWorkspaceProps {
  projectUuid: string
  routeDefinitionUuid?: string
}

type WorkspaceTab = 'draft' | 'versions' | 'bindings'

const executableTypes = new Set<DefinitionType>(['MAPPING', 'PACKAGE', 'PROCEDURE', 'LOAD_PLAN'])
const bindingTypes = new Set<DefinitionType>(['MAPPING', 'REUSABLE_MAPPING', 'PROCEDURE'])

const fallbackTypes: DefinitionTypeDescriptor[] = DEFINITION_TYPES.map((code) => ({
  code,
  label: code,
  category: code === 'LOAD_PLAN' ? 'ORKESTRASYON' : 'TASARIM',
  folderRequired: ['MAPPING', 'REUSABLE_MAPPING', 'PACKAGE', 'PROCEDURE'].includes(code),
  globalAllowed: ['REUSABLE_MAPPING', 'VARIABLE', 'SEQUENCE', 'USER_FUNCTION', 'KNOWLEDGE_MODULE'].includes(code),
  requiredContentFields: [],
}))

function errorMessage(error: unknown, fallback: string) {
  if (error instanceof ApiProblem) return error.message
  if (error instanceof Error) return error.message
  return fallback
}

export function DefinitionsWorkspace({ projectUuid, routeDefinitionUuid }: DefinitionsWorkspaceProps) {
  const { t } = useDefinitionsI18n()
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
  const [bindings, setBindings] = useState<DataBinding[]>([])
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
  const [environmentLoadError, setEnvironmentLoadError] = useState(false)
  const saveDraftAction = useRef<() => Promise<boolean>>(async () => true)

  const selectedDefinition = definitions.find((definition) => definition.uuid === selectedUuid) ?? null
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
    void topologyApi.listEnvironments(projectUuid).then((items) => { if (active) setEnvironments(items.filter((item) => item.status === 'AKTIF')) }).catch(() => { if (active) { setEnvironments([]); setEnvironmentLoadError(true) } })
    return () => { active = false }
  }, [projectUuid])

  const loadDefinition = useCallback(async () => {
    if (!selectedDefinition) return
    const requestNumber = ++definitionRequest.current
    setDraftLoading(true)
    setStatus(null)
    setVersions([])
    setScenarios([])
    setBindings([])
    setSelectedVersionUuid(null)
    try {
      const [nextDraft, nextVersions] = await Promise.all([
        definitionsApi.getDraft(projectUuid, selectedDefinition.uuid),
        definitionsApi.listVersions(projectUuid, selectedDefinition.uuid),
      ])
      if (definitionRequest.current !== requestNumber) return
      setDraft(nextDraft)
      setSchemaVersion(nextDraft?.schemaVersion ?? (selectedDefinition.type === 'MAPPING' || selectedDefinition.type === 'PROCEDURE' ? 2 : 1))
      setContent(nextDraft?.content ?? createDefaultContent(selectedDefinition.type))
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
      setBindings([])
      return
    }
    let active = true
    void definitionsApi
      .listScenarios(projectUuid, selectedDefinition.uuid, selectedVersionUuid)
      .then((rows) => active && setScenarios(rows))
      .catch(() => active && setScenarios([]))
    if (bindingTypes.has(selectedDefinition.type)) {
      void definitionsApi
        .listBindings(projectUuid, selectedDefinition.uuid, selectedVersionUuid)
        .then((rows) => active && setBindings(rows))
        .catch(() => active && setBindings([]))
    }
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
        schemaVersion,
        content,
      )
      setDraft(saved)
      setContent(saved.content)
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
              projectUuid={projectUuid}
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
                  await definitionsApi.saveDraft(projectUuid, created.uuid, 0, input.type === 'PROCEDURE' ? 2 : 1, initialContent)
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
            <div className="definition-empty-workbench">
              <Layers3 aria-hidden="true" />
              <h2>{t('selectDefinition')}</h2>
            </div>
          ) : (
            <>
              <header className="definition-document-header">
                <div className="definition-document-identity">
                  <div className="definition-document-meta">
                    <span className="definition-type-chip">{typeLabel(selectedDefinition.type)}</span>
                    <span><code>{selectedDefinition.code}</code></span>
                  </div>
                  <h2>{selectedDefinition.name}</h2>
                  {selectedDefinition.description && <p>{selectedDefinition.description}</p>}
                </div>
                {tab === 'draft' && !draftLoading && canWrite && (
                  <div className="definition-document-save">
                    {dirty && <span className="definition-unsaved-state">{t('unsaved')}</span>}
                    <AntActionButton tone="primary" type="button" disabled={saving || !dirty} onClick={() => void saveDraft()}>
                      {saving ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Save size={16} aria-hidden="true" />}
                      {saving ? t('saving') : t('saveDraft')}
                    </AntActionButton>
                  </div>
                )}
              </header>

              {bindingTypes.has(selectedDefinition.type) && selectedDefinition.type !== 'PROCEDURE' ? <TabBar className="definition-tabs" role="tablist" aria-label={t('details')} onKeyDown={(event) => {
                if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
                const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
                const current = buttons.indexOf(document.activeElement as HTMLButtonElement)
                const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (current + (event.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length
                event.preventDefault(); buttons[next]?.focus(); buttons[next]?.click()
              }}>
                <AntActionButton tone="ghost" id="definition-tab-draft" type="button" role="tab" tabIndex={tab === 'draft' ? 0 : -1} aria-selected={tab === 'draft'} aria-controls="definition-panel-draft" onClick={() => setTab('draft')}>
                  <FileCode2 size={16} aria-hidden="true" /> {t('draft')}
                  {dirty && <span className="definition-dirty-dot" aria-label={t('unsaved')} />}
                </AntActionButton>
                <AntActionButton tone="ghost" id="definition-tab-bindings" type="button" role="tab" tabIndex={tab === 'bindings' ? 0 : -1} aria-selected={tab === 'bindings'} aria-controls="definition-panel-bindings" onClick={() => setTab('bindings')}>
                  <GitBranch size={16} aria-hidden="true" /> {t('binding')} <span className="definition-count">{bindings.length}</span>
                </AntActionButton>
              </TabBar> : null}

              {draftLoading ? (
                <div className="definition-state"><LoaderCircle className="spin" aria-hidden="true" /> {t('loading')}</div>
              ) : tab === 'draft' ? (
                <section id="definition-panel-draft" className="definition-editor-panel" role="tabpanel" aria-labelledby="definition-tab-draft">
                  <fieldset className="definition-readonly-gate" disabled={!canWrite}>{selectedDefinition.type === 'MAPPING' && isMappingContent(content) ? (
                    <MappingGrid projectUuid={projectUuid} value={content} onChange={updateContent} />
                  ) : selectedDefinition.type === 'PROCEDURE' && isProcedureContent(content) ? (
                    <ProcedureEditor projectUuid={projectUuid} definition={selectedDefinition} value={content} onChange={updateContent} limits={capabilities?.procedure} />
                  ) : selectedDefinition.type === 'PACKAGE' ? (
                    <PackageEditor projectUuid={projectUuid} definitionUuid={selectedDefinition.uuid} value={content} onChange={updateContent} onOpenDefinition={(uuid) => navigateFromExplorer(`/project/objects/definitions/${encodeURIComponent(uuid)}`)} />
                  ) : !['MAPPING', 'PROCEDURE', 'REUSABLE_MAPPING'].includes(selectedDefinition.type) ? (
                    <StructuredDraftEditor projectUuid={projectUuid} type={selectedDefinition.type} value={content} onChange={updateContent} />
                  ) : (
                    <div className="definition-state definition-state--error"><AlertCircle aria-hidden="true" /><p>{t('unsupportedDraftShape')}</p>{canWrite && <AntActionButton tone="secondary" type="button" onClick={() => updateContent(createDefaultContent(selectedDefinition.type))}>{t('resetStructuredDraft')}</AntActionButton>}</div>
                  )}</fieldset>
                  {selectedDefinition.type === 'VARIABLE' && <VariableHistory key={selectedDefinition.uuid} projectUuid={projectUuid} definitionUuid={selectedDefinition.uuid} />}
                </section>
              ) : tab === 'versions' ? (
                <VersionsPanel
                  projectUuid={projectUuid}
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
              ) : (
                <BindingsPanel
                  projectUuid={projectUuid}
                  definition={selectedDefinition}
                  selectedVersion={selectedVersion}
                  versions={versions}
                  selectVersion={setSelectedVersionUuid}
                  bindings={bindings}
                  onCreated={(binding) => setBindings((current) => [binding, ...current])}
                  onError={(text) => setStatus({ tone: 'error', text })}
                  canWrite={canWrite}
                />
              )}
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
  const [environmentUuid, setEnvironmentUuid] = useState('')
  const [preparingScenarioUuid, setPreparingScenarioUuid] = useState('')
  const [prepared, setPrepared] = useState<Record<string, Publication>>({})
  useEffect(() => {
    if (!props.environments.some((item) => item.uuid === environmentUuid)) setEnvironmentUuid(props.environments[0]?.uuid ?? '')
  }, [environmentUuid, props.environments])
  async function prepare(scenario: Scenario) {
    if (!environmentUuid) return
    setPreparingScenarioUuid(scenario.uuid)
    try {
      const publication = await operationsApi.createPublication(props.projectUuid, scenario.uuid, environmentUuid)
      setPrepared((current) => ({ ...current, [scenario.uuid]: publication }))
    } catch (error) { props.onError(errorMessage(error, t('requestError'))) }
    finally { setPreparingScenarioUuid('') }
  }
  return (
    <section id="definition-panel-versions" className="definition-version-layout" role="tabpanel" aria-labelledby="definition-tab-versions">
      <div className="definition-version-column">
        {props.canWrite && <form className="definition-version-form" onSubmit={props.createVersion}>
          <label>
            <span>{t('versionDescription')}</span>
            <AntInput value={props.versionDescription} placeholder={t('versionDescriptionPlaceholder')} onChange={(event) => props.setVersionDescription(event.target.value)} />
          </label>
          <AntActionButton tone="primary" type="submit" disabled={!props.draft || props.dirty || props.creatingVersion}>
            {props.creatingVersion ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}
            {props.creatingVersion ? t('creatingVersion') : t('createVersion')}
          </AntActionButton>
        </form>}
        <div className="definition-version-list">
          {props.versions.length === 0 ? <p className="definition-state">{t('noVersions')}</p> : props.versions.map((version) => (
            <AntActionButton tone="ghost" key={version.uuid} type="button" className={version.uuid === props.selectedVersionUuid ? 'is-selected' : ''} onClick={() => props.setSelectedVersionUuid(version.uuid)}>
              <span className="definition-version-number">v{version.versionNumber}</span>
              <span><strong>{version.description || `${t('version')} ${version.versionNumber}`}</strong><small>{formatter.format(new Date(version.createdAt))}</small></span>
              <ChevronRight size={16} aria-hidden="true" />
            </AntActionButton>
          ))}
        </div>
      </div>
      <div className="definition-scenario-column">
        <div className="definition-panel-heading">
          <div><h3>{t('scenarios')}</h3>{selected && <p>{t('version')} {selected.versionNumber} · <code>{selected.contentHash.slice(0, 12)}</code></p>}</div>
          {props.canValidate && props.executable && selected && (
            <AntActionButton tone="primary" type="button" disabled={props.compiling} onClick={props.compileScenario}>
              {props.compiling ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <GitBranch size={16} aria-hidden="true" />}
              {props.compiling ? t('compiling') : t('compile')}
            </AntActionButton>
          )}
        </div>
        {!selected ? <p className="definition-state">{t('selectVersion')}</p> : !props.executable ? <p className="definition-state">{t('notExecutable')}</p> : props.scenarios.length === 0 ? <p className="definition-state">{t('noScenarios')}</p> : (
          <div className="definition-scenario-list">
            {props.scenarios.map((scenario) => (
              <article key={scenario.uuid}>
                <div><strong>{t('scenarioVersion', { version: scenario.scenarioVersion })}</strong><span>{t('planVersion', { version: scenario.planVersion })}</span></div>
                <code>{scenario.planHash}</code>
                <time dateTime={scenario.createdAt}>{formatter.format(new Date(scenario.createdAt))}</time>
                {props.canPublish && <div className="definition-runnable-actions">
                  <label><span>{t('environment')}</span><FormSelect value={environmentUuid} onChange={(event) => setEnvironmentUuid(event.target.value)} disabled={props.environments.length === 0}>{props.environments.map((environment) => <option key={environment.uuid} value={environment.uuid}>{environment.name} · {environment.code}</option>)}</FormSelect></label>
                  {prepared[scenario.uuid] ? <Link className="definition-button definition-button--quiet" to={`/project/publications/${prepared[scenario.uuid]!.uuid}`}>{t('reviewRunnableVersion')}</Link> : <AntActionButton tone="primary" type="button" disabled={!environmentUuid || preparingScenarioUuid === scenario.uuid} onClick={() => void prepare(scenario)}>{preparingScenarioUuid === scenario.uuid ? t('preparingRunnableVersion') : t('prepareRunnableVersion')}</AntActionButton>}
                </div>}
              </article>
            ))}
          </div>
        )}
      </div>
    </section>
  )
}

interface BindingsPanelProps {
  projectUuid: string
  definition: Definition
  selectedVersion: DefinitionVersion | null
  versions: DefinitionVersion[]
  selectVersion: (uuid: string) => void
  bindings: DataBinding[]
  onCreated: (binding: DataBinding) => void
  onError: (message: string) => void
  canWrite: boolean
}

function BindingsPanel(props: BindingsPanelProps) {
  const { t } = useDefinitionsI18n()
  const [input, setInput] = useState<{
    nodeCode: string
    role: 'KAYNAK' | 'HEDEF'
    dataObjectUuid: string
    schemaSnapshotUuid: string
  }>({ nodeCode: '', role: 'KAYNAK', dataObjectUuid: '', schemaSnapshotUuid: '' })
  const [saving, setSaving] = useState(false)
  const [candidates, setCandidates] = useState<BindingCandidate[]>([])
  const [loadingCandidates, setLoadingCandidates] = useState(false)
  const [candidateError, setCandidateError] = useState('')
  const nodes = useMemo(
    () => bindingNodes(props.definition.type, props.selectedVersion?.content),
    [props.definition.type, props.selectedVersion?.content],
  )
  const availableNodes = useMemo(
    () => unboundNodes(nodes, props.bindings),
    [nodes, props.bindings],
  )
  const selectedCandidate = candidates.find((candidate) => candidate.schemaSnapshotUuid === input.schemaSnapshotUuid)

  useEffect(() => {
    setInput({ nodeCode: '', role: 'KAYNAK', dataObjectUuid: '', schemaSnapshotUuid: '' })
    setCandidates([])
    setCandidateError('')
    if (!props.selectedVersion) return
    let active = true
    setLoadingCandidates(true)
    void definitionsApi.listBindingCandidates(
      props.projectUuid, props.definition.uuid, props.selectedVersion.uuid,
    ).then((items) => {
      if (active) setCandidates(items)
    }).catch((error) => {
      if (active) setCandidateError(errorMessage(error, t('bindingCandidatesError')))
    }).finally(() => {
      if (active) setLoadingCandidates(false)
    })
    return () => { active = false }
  }, [props.definition.uuid, props.projectUuid, props.selectedVersion, t])

  useEffect(() => {
    if (nodes.length === 0 || availableNodes.length === 0) return
    if (availableNodes.some((node) => node.code === input.nodeCode)) return
    const node = availableNodes[0]!
    setInput((current) => ({ ...current, nodeCode: node.code, role: node.role }))
  }, [availableNodes, input.nodeCode, nodes.length])

  function selectNode(nodeCode: string) {
    const node = availableNodes.find((candidate) => candidate.code === nodeCode)
    if (!node) return
    setInput({ ...input, nodeCode: node.code, role: node.role })
  }

  function selectCandidate(snapshotUuid: string) {
    const candidate = candidates.find((item) => item.schemaSnapshotUuid === snapshotUuid)
    setInput({
      ...input,
      schemaSnapshotUuid: candidate?.schemaSnapshotUuid ?? '',
      dataObjectUuid: candidate?.dataObjectUuid ?? '',
    })
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!props.selectedVersion) return
    setSaving(true)
    try {
      const created = await definitionsApi.createBinding(props.projectUuid, props.definition.uuid, props.selectedVersion.uuid, input)
      props.onCreated(created)
      setInput((current) => ({ ...current, nodeCode: '', dataObjectUuid: '', schemaSnapshotUuid: '' }))
    } catch (error) {
      props.onError(errorMessage(error, t('requestError')))
    } finally {
      setSaving(false)
    }
  }
  return (
    <section id="definition-panel-bindings" className="definition-bindings-panel" role="tabpanel" aria-labelledby="definition-tab-bindings">
      <div className="definition-panel-heading">
        <h3>{t('binding')}</h3>
        <label><span>{t('version')}</span><FormSelect value={props.selectedVersion?.uuid ?? ''} onChange={(event) => props.selectVersion(event.target.value)}><option value="">—</option>{props.versions.map((version) => <option key={version.uuid} value={version.uuid}>v{version.versionNumber}</option>)}</FormSelect></label>
      </div>
      {props.canWrite && <form className="definition-binding-form" onSubmit={submit}>
        {nodes.length > 0 ? <>
          <label><span>{t('stepOrDataset')}</span><FormSelect required value={input.nodeCode} onChange={(event) => selectNode(event.target.value)}><option value="">—</option>{availableNodes.map((node) => <option key={node.code} value={node.code}>{node.name} · {node.code} · {node.role === 'KAYNAK' ? t('source') : t('target')}</option>)}</FormSelect></label>
          <label><span>{t('catalogSnapshot')}</span><FormSelect required value={input.schemaSnapshotUuid} onChange={(event) => selectCandidate(event.target.value)} disabled={loadingCandidates || candidates.length === 0}><option value="">—</option>{candidates.map((candidate) => <option key={candidate.schemaSnapshotUuid} value={candidate.schemaSnapshotUuid}>{candidateLabel(candidate)}</option>)}</FormSelect></label>
          <label><span>{t('role')}</span><AntInput readOnly value={input.role === 'KAYNAK' ? t('source') : t('target')} /></label>
          <label><span>{t('environments')}</span><AntInput readOnly value={selectedCandidate?.environmentCodes.join(', ') ?? '—'} /></label>
        </> : <p className="definition-state">{t('structuredBindingUnavailable')}</p>}
        <AntActionButton tone="primary" type="submit" disabled={!props.selectedVersion || saving || nodes.length === 0 || !input.nodeCode || !selectedCandidate}>{saving ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}{t('saveBinding')}</AntActionButton>
      </form>}
      {loadingCandidates && <p className="definition-state"><LoaderCircle className="spin" aria-hidden="true" /> {t('loadingBindingCandidates')}</p>}
      {candidateError && <p className="definition-state definition-state--error" role="alert"><AlertCircle aria-hidden="true" /> {candidateError}</p>}
      {!loadingCandidates && !candidateError && nodes.length > 0 && candidates.length === 0 && <p className="definition-state">{t('noTrustedSnapshots')}</p>}
      {!loadingCandidates && nodes.length > 0 && availableNodes.length === 0 && <p className="definition-state">{t('allNodesBound')}</p>}
      {props.bindings.length === 0 ? <p className="definition-state">{t('noBindings')}</p> : (
        <div className="definition-binding-list">{props.bindings.map((binding) => {
          const candidate = candidates.find((item) => item.schemaSnapshotUuid === binding.schemaSnapshotUuid)
          return <article key={binding.uuid}><span className={`definition-role definition-role--${binding.role === 'KAYNAK' ? 'source' : 'target'}`}>{binding.role === 'KAYNAK' ? t('source') : t('target')}</span><strong>{binding.nodeCode}</strong><dl>{candidate ? <><div><dt>{t('catalogObject')}</dt><dd>{candidate.connectionCode} · {candidate.physicalSchemaReference}.{candidate.objectReference}</dd></div><div><dt>{t('snapshotFingerprint')}</dt><dd><code title={candidate.snapshotFingerprint}>{candidate.snapshotFingerprint.slice(0, 12)}…{candidate.snapshotFingerprint.slice(-8)}</code></dd></div></> : <div><dt>{t('catalogObject')}</dt><dd>{t('legacyBindingUnavailable')}</dd></div>}</dl></article>
        })}</div>
      )}
    </section>
  )
}

interface CreateDefinitionEditorProps {
  projectUuid: string
  folders: Folder[]
  types: DefinitionTypeDescriptor[]
  initialType: DefinitionType | null
  initialFolderUuid: string | null
  creating: boolean
  close: () => void
  onCreate: (input: NewDefinitionInput, content: unknown) => Promise<void>
}

function CreateDefinitionEditor({ projectUuid, folders, types, initialType, initialFolderUuid, creating, close, onCreate }: CreateDefinitionEditorProps) {
  const { t } = useDefinitionsI18n()
  const [input, setInput] = useState<NewDefinitionInput>({ folderUuid: initialFolderUuid, type: initialType ?? 'MAPPING', code: '', name: '', description: '' })
  const [initialContent, setInitialContent] = useState<unknown>(() => createDefaultContent(initialType ?? 'MAPPING'))
  const descriptor = types.find((type) => type.code === input.type)
  const fixedComponentType = initialType !== null && !descriptor?.folderRequired
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (descriptor?.folderRequired && !input.folderUuid) return
    await onCreate(input, initialContent)
  }
  return (
    <section className="definition-new-editor" aria-label={initialType ? t('newDefinitionNamed', { name: t(definitionTypeKey[initialType]) }) : t('newDefinition')}>
      <form onSubmit={submit}>
        <header className="definition-document-header definition-new-editor-header"><div><div className="definition-document-meta"><span className="definition-type-chip">{t(definitionTypeKey[input.type])}</span><span>{t('newDefinition')}</span></div><h2>{input.name || t('newDefinitionNamed', { name: t(definitionTypeKey[input.type]) })}</h2></div><div className="definition-editor-actions"><AntActionButton tone="secondary" type="button" onClick={close}>{t('cancel')}</AntActionButton><AntActionButton tone="primary" type="submit" disabled={creating || !input.code || !input.name || Boolean(descriptor?.folderRequired && !input.folderUuid)}>{creating ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Save size={16} aria-hidden="true" />}{creating ? t('creating') : t('saveDraft')}</AntActionButton></div></header>
        <fieldset disabled={creating} className="definition-new-editor-fields">
          <div className="definition-form-grid">
            {!initialType && <label><span>{t('type')}</span><FormSelect value={input.type} onChange={(event) => { const type = event.target.value as DefinitionType; setInput({ ...input, type, folderUuid: null }); setInitialContent(createDefaultContent(type)) }}>{types.map((type) => <option key={type.code} value={type.code}>{t(definitionTypeKey[type.code])}</option>)}</FormSelect></label>}
            {!fixedComponentType && <label><span>{t('folder')}</span><FormSelect required={descriptor?.folderRequired} value={input.folderUuid ?? ''} onChange={(event) => setInput({ ...input, folderUuid: event.target.value || null })}><option value="">{t('noFolder')}</option>{folders.filter((folder) => folder.status === 'AKTIF').map((folder) => <option key={folder.uuid} value={folder.uuid}>{folder.name} · {folder.code}</option>)}</FormSelect>{descriptor?.folderRequired && !input.folderUuid && <small>{t('folderRequired')}</small>}</label>}
            <label><span>{t('code')}</span><AntInput autoFocus required pattern="[A-Z][A-Z0-9_]{0,99}" placeholder="CUSTOMER_LOAD" value={input.code} onChange={(event) => setInput({ ...input, code: event.target.value.toLocaleUpperCase('en-US').replace(/[^A-Z0-9_]/g, '') })} /></label>
            <label><span>{t('name')}</span><AntInput required value={input.name} onChange={(event) => setInput({ ...input, name: event.target.value })} /></label>
            <label className="definition-form-grid--wide"><span>{t('description')}</span><AntInput.TextArea rows={3} value={input.description} onChange={(event) => setInput({ ...input, description: event.target.value })} /></label>
          </div>
        </fieldset>
        <fieldset disabled={creating} className="definition-readonly-gate definition-new-editor-content">
          {input.type === 'MAPPING' && isMappingContent(initialContent) ? <MappingGrid projectUuid={projectUuid} value={initialContent} onChange={setInitialContent} />
            : input.type === 'PROCEDURE' && isProcedureContent(initialContent) ? <ProcedureEditor projectUuid={projectUuid} definition={{ code: input.code, name: input.name, description: input.description }} value={initialContent} onChange={setInitialContent} />
              : input.type === 'PACKAGE' ? <PackageEditor projectUuid={projectUuid} definitionUuid="__new__" value={initialContent} onChange={setInitialContent} />
                : <StructuredDraftEditor projectUuid={projectUuid} type={input.type} value={initialContent} onChange={setInitialContent} />}
        </fieldset>
      </form>
    </section>
  )
}
