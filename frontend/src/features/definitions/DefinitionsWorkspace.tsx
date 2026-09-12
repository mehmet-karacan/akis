import {
  AlertCircle,
  Check,
  ChevronRight,
  CirclePlus,
  FileCode2,
  FolderInput,
  FolderPlus,
  GitBranch,
  Layers3,
  LoaderCircle,
  Plus,
  RefreshCw,
  Save,
  ShieldCheck,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiProblem } from '../../core/api/client'
import { usePendingChanges } from '../../core/navigation/PendingChangesContext'
import { Dialog } from '../../core/ui/Dialog'
import { executionApi } from '../execution/api'
import type { ProjectCapabilities } from '../execution/types'
import { operationsApi } from '../operations/api'
import type { Publication } from '../operations/types'
import { topologyApi, type Environment } from '../topology/api'
import { definitionsApi } from './api'
import { bindingNodes, candidateLabel, unboundNodes } from './bindingCatalog'
import { createDefaultContent, isMappingContent, isProcedureContent, supportsVisualEditor } from './defaults'
import { definitionTypeKey, useDefinitionsI18n } from './i18n'
import { JsonDraftEditor } from './JsonDraftEditor'
import { MappingGrid } from './MappingGrid'
import { ProcedureEditor } from './ProcedureEditor'
import { StructuredDraftEditor } from './StructuredDraftEditor'
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
}

type WorkspaceTab = 'draft' | 'versions' | 'bindings'
type EditorMode = 'visual' | 'json'

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

export function DefinitionsWorkspace({ projectUuid }: DefinitionsWorkspaceProps) {
  const { t } = useDefinitionsI18n()
  const { setPendingChanges } = usePendingChanges()
  const [searchParams, setSearchParams] = useSearchParams()
  const initialSelection = useRef({ projectUuid, uuid: searchParams.get('definition') })
  if (initialSelection.current.projectUuid !== projectUuid) {
    initialSelection.current = { projectUuid, uuid: searchParams.get('definition') }
  }
  const definitionRequest = useRef(0)
  const [definitions, setDefinitions] = useState<Definition[]>([])
  const [folders, setFolders] = useState<Folder[]>([])
  const [types, setTypes] = useState<DefinitionTypeDescriptor[]>(fallbackTypes)
  const [selectedUuid, setSelectedUuid] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(false)
  const [showCreateFolder, setShowCreateFolder] = useState(false)
  const [showMoveDefinition, setShowMoveDefinition] = useState(false)
  const [pendingDefinitionUuid, setPendingDefinitionUuid] = useState<string | null>(null)
  const [folderToMove, setFolderToMove] = useState<Folder | null>(null)
  const [creating, setCreating] = useState(false)
  const [creatingFolder, setCreatingFolder] = useState(false)
  const [movingDefinition, setMovingDefinition] = useState(false)
  const [movingFolder, setMovingFolder] = useState(false)
  const [tab, setTab] = useState<WorkspaceTab>('draft')
  const [editorMode, setEditorMode] = useState<EditorMode>('visual')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [content, setContent] = useState<unknown>({})
  const [schemaVersion, setSchemaVersion] = useState(1)
  const [draftLoading, setDraftLoading] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [jsonValid, setJsonValid] = useState(true)
  const [saving, setSaving] = useState(false)
  const [versions, setVersions] = useState<DefinitionVersion[]>([])
  const [versionDescription, setVersionDescription] = useState('')
  const [creatingVersion, setCreatingVersion] = useState(false)
  const [selectedVersionUuid, setSelectedVersionUuid] = useState<string | null>(null)
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  const [bindings, setBindings] = useState<DataBinding[]>([])
  const [compiling, setCompiling] = useState(false)
  const [status, setStatus] = useState<{ tone: 'success' | 'error' | 'info'; text: string } | null>(null)
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
    const requestedUuid = searchParams.get('definition')
    if (!requestedUuid || requestedUuid === selectedUuid || dirty
        || !definitions.some((definition) => definition.uuid === requestedUuid)) return
    definitionRequest.current += 1
    setSelectedUuid(requestedUuid)
    setTab('draft')
  }, [definitions, dirty, searchParams, selectedUuid])

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
      setJsonValid(true)
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
    if (!selectedDefinition || saving || !jsonValid) return false
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
      setStatus({ tone: 'success', text: t('saved') })
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
    setPendingChanges(dirty ? { save: () => saveDraftAction.current() } : null)
    return () => setPendingChanges(null)
  }, [dirty, setPendingChanges])

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
    setSelectedUuid(uuid)
    setTab('draft')
    const nextParams = new URLSearchParams(searchParams)
    nextParams.set('definition', uuid)
    nextParams.delete('tab')
    setSearchParams(nextParams, { replace: true })
  }

  return (
    <div className="definitions-workspace" onKeyDown={handleWorkspaceKeyDown}>
      <header className="definitions-titlebar">
        <div>
          <p className="definition-eyebrow">{t('designControl')}</p>
          <h1>{t('title')}</h1>
          <p>{t('subtitle')}</p>
        </div>
        <div className="definition-title-actions"><button className="definition-button definition-button--quiet" type="button" onClick={() => setShowCreateFolder(true)}><FolderPlus size={17} aria-hidden="true" /> {t('newFolder')}</button><button className="definition-button definition-button--primary" type="button" onClick={() => setShowCreate(true)}><CirclePlus size={17} aria-hidden="true" /> {t('newDefinition')}</button></div>
      </header>
      {capabilityError && <div className="definition-notice definition-notice--info" role="status"><AlertCircle size={16} aria-hidden="true" /><span>{t('capabilityUnavailable')}</span></div>}
      {environmentLoadError && <div className="definition-notice definition-notice--error" role="alert"><AlertCircle size={16} aria-hidden="true" /><span>{t('environmentLoadError')}</span></div>}

      <div className="definitions-shell definitions-shell--workbench">
        <section className="definition-workbench" aria-label={t('details')}>
          {loading && <div className="definition-state"><LoaderCircle className="spin" aria-hidden="true" /> {t('loading')}</div>}
          {loadError && <div className="definition-state definition-state--error"><AlertCircle aria-hidden="true" /><p>{loadError}</p><button className="definition-button definition-button--quiet" type="button" onClick={() => void loadWorkspace()}>{t('retry')}</button></div>}
          {status && (
            <div className={`definition-notice definition-notice--${status.tone}`} role={status.tone === 'error' ? 'alert' : 'status'}>
              {status.tone === 'success' ? <Check size={16} aria-hidden="true" /> : <AlertCircle size={16} aria-hidden="true" />}
              <span>{status.text}</span>
              {status.tone === 'error' && selectedDefinition && draft ? (
                <button type="button" onClick={() => void loadDefinition()}>{t('reloadDraft')}</button>
              ) : null}
            </div>
          )}
          {!selectedDefinition ? (
            <div className="definition-empty-workbench">
              <Layers3 aria-hidden="true" />
              <h2>{t('selectDefinition')}</h2>
            </div>
          ) : (
            <>
              <header className="definition-document-header">
                <div>
                  <div className="definition-document-meta">
                    <span className="definition-type-chip">{typeLabel(selectedDefinition.type)}</span>
                    <span>{selectedDefinition.status === 'AKTIF' ? t('active') : selectedDefinition.status}</span>
                    <span><code>{selectedDefinition.code}</code></span>
                  </div>
                  <h2>{selectedDefinition.name}</h2>
                  {selectedDefinition.description && <p>{selectedDefinition.description}</p>}
                </div>
                <div className="definition-document-actions">
                  <button className="definition-button definition-button--quiet" type="button" onClick={() => setShowMoveDefinition(true)}>
                    <FolderInput size={16} aria-hidden="true" /> {t('moveDefinition')}
                  </button>
                  <button className="definition-icon-button" type="button" aria-label={t('reloadDraft')} onClick={() => void loadDefinition()}>
                    <RefreshCw size={17} aria-hidden="true" />
                  </button>
                </div>
              </header>

              <div className="definition-tabs" role="tablist" aria-label={t('details')} onKeyDown={(event) => {
                if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
                const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
                const current = buttons.indexOf(document.activeElement as HTMLButtonElement)
                const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (current + (event.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length
                event.preventDefault(); buttons[next]?.focus(); buttons[next]?.click()
              }}>
                <button id="definition-tab-draft" type="button" role="tab" tabIndex={tab === 'draft' ? 0 : -1} aria-selected={tab === 'draft'} aria-controls="definition-panel-draft" onClick={() => setTab('draft')}>
                  <FileCode2 size={16} aria-hidden="true" /> {t('draft')}
                  {dirty && <span className="definition-dirty-dot" aria-label={t('unsaved')} />}
                </button>
                <button id="definition-tab-versions" type="button" role="tab" tabIndex={tab === 'versions' ? 0 : -1} aria-selected={tab === 'versions'} aria-controls="definition-panel-versions" onClick={() => setTab('versions')}>
                  <ShieldCheck size={16} aria-hidden="true" /> {t('versions')} <span className="definition-count">{versions.length}</span>
                </button>
                {bindingTypes.has(selectedDefinition.type) && (
                  <button id="definition-tab-bindings" type="button" role="tab" tabIndex={tab === 'bindings' ? 0 : -1} aria-selected={tab === 'bindings'} aria-controls="definition-panel-bindings" onClick={() => setTab('bindings')}>
                    <GitBranch size={16} aria-hidden="true" /> {t('binding')} <span className="definition-count">{bindings.length}</span>
                  </button>
                )}
              </div>

              {draftLoading ? (
                <div className="definition-state"><LoaderCircle className="spin" aria-hidden="true" /> {t('loading')}</div>
              ) : tab === 'draft' ? (
                <section id="definition-panel-draft" className="definition-editor-panel" role="tabpanel" aria-labelledby="definition-tab-draft">
                  <div className="definition-editor-toolbar">
                    <div className="definition-draft-state">
                      <strong>{draft ? t('draftVersion', { version: draft.version }) : t('noDraft')}</strong>
                      {dirty && <span>{t('unsaved')}</span>}
                    </div>
                    <div className="definition-editor-actions">
                      {supportsVisualEditor(selectedDefinition.type, schemaVersion) && (
                        <div className="definition-segmented definition-editor-mode" aria-label={t('editorMode')}>
                          <button type="button" aria-pressed={editorMode === 'visual'} onClick={() => setEditorMode('visual')}>{selectedDefinition.type === 'PROCEDURE' ? t('procedureEditor') : t('formEditor')}</button>
                          <button type="button" aria-pressed={editorMode === 'json'} onClick={() => setEditorMode('json')}>{t('advancedJson')}</button>
                        </div>
                      )}
                      <label className="definition-schema-version">
                        <span>{t('schemaVersion')}</span>
                        <input type="number" min="1" value={schemaVersion} onChange={(event) => { setSchemaVersion(Number(event.target.value)); setDirty(true) }} />
                      </label>
                      <button className="definition-button definition-button--primary" type="button" disabled={saving || !dirty || !jsonValid} onClick={() => void saveDraft()}>
                        {saving ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Save size={16} aria-hidden="true" />}
                        {saving ? t('saving') : t('saveDraft')}
                      </button>
                    </div>
                  </div>
                  {selectedDefinition.type === 'MAPPING' && editorMode === 'visual' && isMappingContent(content) ? (
                    <MappingGrid value={content} onChange={updateContent} />
                  ) : selectedDefinition.type === 'PROCEDURE' && supportsVisualEditor(selectedDefinition.type, schemaVersion) && editorMode === 'visual' && isProcedureContent(content) ? (
                    <ProcedureEditor value={content} onChange={updateContent} limits={capabilities?.procedure} />
                  ) : ['VARIABLE', 'SEQUENCE', 'PACKAGE'].includes(selectedDefinition.type) && editorMode === 'visual' ? (
                    <StructuredDraftEditor type={selectedDefinition.type} value={content} onChange={updateContent} />
                  ) : (
                    <JsonDraftEditor value={content} onChange={updateContent} onValidityChange={setJsonValid} />
                  )}
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
                />
              )}
            </>
          )}
        </section>
      </div>

      {showCreate && (
        <CreateDefinitionDialog
          projectUuid={projectUuid}
          folders={folders}
          types={types}
          creating={creating}
          close={() => setShowCreate(false)}
          onCreate={async (input) => {
            setCreating(true)
            try {
              const created = await definitionsApi.createDefinition(projectUuid, input)
              setDefinitions((current) => [created, ...current])
              setSelectedUuid(created.uuid)
              const nextParams = new URLSearchParams(searchParams)
              nextParams.set('definition', created.uuid)
              setSearchParams(nextParams, { replace: true })
              notifyProjectTreeChanged()
              setShowCreate(false)
              setTab('draft')
            } catch (error) {
              setStatus({ tone: 'error', text: errorMessage(error, t('requestError')) })
            } finally {
              setCreating(false)
            }
          }}
        />
      )}
      {showCreateFolder && (
        <CreateFolderDialog
          folders={folders}
          creating={creatingFolder}
          close={() => setShowCreateFolder(false)}
          onCreate={async (input) => {
            setCreatingFolder(true)
            try {
              const created = await definitionsApi.createFolder(projectUuid, input)
              setFolders((current) => [...current, created])
              notifyProjectTreeChanged()
              setShowCreateFolder(false)
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
        <footer className="dialog-actions"><button className="definition-button definition-button--quiet" type="button" onClick={() => setPendingDefinitionUuid(null)}>{t('stay')}</button><button className="definition-button definition-button--quiet" type="button" onClick={() => { const uuid = pendingDefinitionUuid; setPendingDefinitionUuid(null); setDirty(false); if (uuid) applyDefinitionSelection(uuid) }}>{t('discardAndContinue')}</button><button className="definition-button definition-button--primary" type="button" onClick={async () => { const uuid = pendingDefinitionUuid; if (await saveDraft()) { setPendingDefinitionUuid(null); if (uuid) applyDefinitionSelection(uuid) } }}>{t('saveAndContinue')}</button></footer>
      </Dialog>
    </div>
  )
}

interface CreateFolderDialogProps {
  folders: Folder[]
  creating: boolean
  close: () => void
  onCreate: (input: NewFolderInput) => Promise<void>
}

function CreateFolderDialog({ folders, creating, close, onCreate }: CreateFolderDialogProps) {
  const { t } = useDefinitionsI18n()
  const [input, setInput] = useState<NewFolderInput>({ parentUuid: null, type: 'GELISTIRME', code: '', name: '', description: '' })
  async function submit(event: FormEvent) {
    event.preventDefault()
    await onCreate(input)
  }
  return (
    <Dialog open title={t('newFolder')} eyebrow={t('designControl')} closeLabel={t('close')} onClose={close} busy={creating} className="definition-dialog" backdropClassName="definition-dialog-backdrop">
        <form onSubmit={submit}>
          <div className="definition-form-grid">
            <label><span>{t('parentFolder')}</span><select value={input.parentUuid ?? ''} onChange={(event) => setInput({ ...input, parentUuid: event.target.value || null })}><option value="">{t('rootFolder')}</option>{folders.filter((folder) => folder.status === 'AKTIF').map((folder) => <option key={folder.uuid} value={folder.uuid}>{folder.name} · {folder.code}</option>)}</select></label>
            <label><span>{t('folderType')}</span><select value={input.type} onChange={(event) => setInput({ ...input, type: event.target.value as NewFolderInput['type'] })}><option value="GELISTIRME">{t('developmentFolder')}</option><option value="MODEL">{t('modelFolder')}</option><option value="YUKLEME_PLANI">{t('loadPlanFolder')}</option></select></label>
            <label><span>{t('code')}</span><input autoFocus required pattern="[A-Z][A-Z0-9_]{0,99}" placeholder="FINANCE" value={input.code} onChange={(event) => setInput({ ...input, code: event.target.value.toLocaleUpperCase('en-US').replace(/[^A-Z0-9_]/g, '') })} /></label>
            <label><span>{t('name')}</span><input required value={input.name} onChange={(event) => setInput({ ...input, name: event.target.value })} /></label>
            <label className="definition-form-grid--wide"><span>{t('description')}</span><textarea rows={3} value={input.description} onChange={(event) => setInput({ ...input, description: event.target.value })} /></label>
          </div>
          <footer><button className="definition-button definition-button--quiet" type="button" onClick={close}>{t('cancel')}</button><button className="definition-button definition-button--primary" type="submit" disabled={creating}>{creating ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}{creating ? t('creatingFolder') : t('createFolder')}</button></footer>
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
          <label><span>{t('folder')}</span><select autoFocus required={folderRequired} value={folderUuid ?? ''} onChange={(event) => setFolderUuid(event.target.value || null)}><option value="" disabled={folderRequired}>{t('unfiled')}</option>{folders.filter((folder) => folder.status === 'AKTIF').map((folder) => <option key={folder.uuid} value={folder.uuid}>{folder.name} · {folder.code}</option>)}</select>{folderRequired && !folderUuid ? <small className="definition-field-error">{t('folderRequired')}</small> : null}</label>
          <footer><button className="definition-button definition-button--quiet" type="button" onClick={close}>{t('cancel')}</button><button className="definition-button definition-button--primary" type="submit" disabled={moving || folderUuid === definition.folderUuid || (folderRequired && !folderUuid)}>{moving ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <FolderInput size={16} aria-hidden="true" />}{moving ? t('movingDefinition') : t('move')}</button></footer>
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
          <label><span>{t('parentFolder')}</span><select autoFocus value={parentUuid ?? ''} onChange={(event) => setParentUuid(event.target.value || null)}><option value="">{t('rootFolder')}</option>{folders.filter((candidate) => candidate.status === 'AKTIF' && !unavailable.has(candidate.uuid)).map((candidate) => <option key={candidate.uuid} value={candidate.uuid}>{candidate.name} · {candidate.code}</option>)}</select></label>
          <footer><button className="definition-button definition-button--quiet" type="button" onClick={close}>{t('cancel')}</button><button className="definition-button definition-button--primary" type="submit" disabled={moving || parentUuid === folder.parentUuid}>{moving ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <FolderInput size={16} aria-hidden="true" />}{moving ? t('movingFolder') : t('move')}</button></footer>
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
        <form className="definition-version-form" onSubmit={props.createVersion}>
          <label>
            <span>{t('versionDescription')}</span>
            <input value={props.versionDescription} placeholder={t('versionDescriptionPlaceholder')} onChange={(event) => props.setVersionDescription(event.target.value)} />
          </label>
          <button className="definition-button definition-button--primary" type="submit" disabled={!props.draft || props.dirty || props.creatingVersion}>
            {props.creatingVersion ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}
            {props.creatingVersion ? t('creatingVersion') : t('createVersion')}
          </button>
        </form>
        <div className="definition-version-list">
          {props.versions.length === 0 ? <p className="definition-state">{t('noVersions')}</p> : props.versions.map((version) => (
            <button key={version.uuid} type="button" className={version.uuid === props.selectedVersionUuid ? 'is-selected' : ''} onClick={() => props.setSelectedVersionUuid(version.uuid)}>
              <span className="definition-version-number">v{version.versionNumber}</span>
              <span><strong>{version.description || `${t('version')} ${version.versionNumber}`}</strong><small>{formatter.format(new Date(version.createdAt))}</small></span>
              <ChevronRight size={16} aria-hidden="true" />
            </button>
          ))}
        </div>
      </div>
      <div className="definition-scenario-column">
        <div className="definition-panel-heading">
          <div><h3>{t('scenarios')}</h3>{selected && <p>{t('version')} {selected.versionNumber} · <code>{selected.contentHash.slice(0, 12)}</code></p>}</div>
          {props.executable && selected && (
            <button className="definition-button definition-button--primary" type="button" disabled={props.compiling} onClick={props.compileScenario}>
              {props.compiling ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <GitBranch size={16} aria-hidden="true" />}
              {props.compiling ? t('compiling') : t('compile')}
            </button>
          )}
        </div>
        {!selected ? <p className="definition-state">{t('selectVersion')}</p> : !props.executable ? <p className="definition-state">{t('notExecutable')}</p> : props.scenarios.length === 0 ? <p className="definition-state">{t('noScenarios')}</p> : (
          <div className="definition-scenario-list">
            {props.scenarios.map((scenario) => (
              <article key={scenario.uuid}>
                <div><strong>{t('scenarioVersion', { version: scenario.scenarioVersion })}</strong><span>{t('planVersion', { version: scenario.planVersion })}</span></div>
                <code>{scenario.planHash}</code>
                <time dateTime={scenario.createdAt}>{formatter.format(new Date(scenario.createdAt))}</time>
                <div className="definition-runnable-actions">
                  <label><span>{t('environment')}</span><select value={environmentUuid} onChange={(event) => setEnvironmentUuid(event.target.value)} disabled={props.environments.length === 0}>{props.environments.map((environment) => <option key={environment.uuid} value={environment.uuid}>{environment.name} · {environment.code}</option>)}</select></label>
                  {prepared[scenario.uuid] ? <Link className="definition-button definition-button--quiet" to={`/projects/${props.projectUuid}/publications/${prepared[scenario.uuid]!.uuid}`}>{t('reviewRunnableVersion')}</Link> : <button className="definition-button definition-button--primary" type="button" disabled={!environmentUuid || preparingScenarioUuid === scenario.uuid} onClick={() => void prepare(scenario)}>{preparingScenarioUuid === scenario.uuid ? t('preparingRunnableVersion') : t('prepareRunnableVersion')}</button>}
                </div>
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
        <label><span>{t('version')}</span><select value={props.selectedVersion?.uuid ?? ''} onChange={(event) => props.selectVersion(event.target.value)}><option value="">—</option>{props.versions.map((version) => <option key={version.uuid} value={version.uuid}>v{version.versionNumber}</option>)}</select></label>
      </div>
      <form className="definition-binding-form" onSubmit={submit}>
        {nodes.length > 0 ? <>
          <label><span>{t('stepOrDataset')}</span><select required value={input.nodeCode} onChange={(event) => selectNode(event.target.value)}><option value="">—</option>{availableNodes.map((node) => <option key={node.code} value={node.code}>{node.name} · {node.code} · {node.role === 'KAYNAK' ? t('source') : t('target')}</option>)}</select></label>
          <label><span>{t('catalogSnapshot')}</span><select required value={input.schemaSnapshotUuid} onChange={(event) => selectCandidate(event.target.value)} disabled={loadingCandidates || candidates.length === 0}><option value="">—</option>{candidates.map((candidate) => <option key={candidate.schemaSnapshotUuid} value={candidate.schemaSnapshotUuid}>{candidateLabel(candidate)}</option>)}</select></label>
          <label><span>{t('role')}</span><input readOnly value={input.role === 'KAYNAK' ? t('source') : t('target')} /></label>
          <label><span>{t('environments')}</span><input readOnly value={selectedCandidate?.environmentCodes.join(', ') ?? '—'} /></label>
        </> : <p className="definition-state">{t('structuredBindingUnavailable')}</p>}
        <button className="definition-button definition-button--primary" type="submit" disabled={!props.selectedVersion || saving || nodes.length === 0 || !input.nodeCode || !selectedCandidate}>{saving ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}{t('saveBinding')}</button>
      </form>
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

interface CreateDefinitionDialogProps {
  projectUuid: string
  folders: Folder[]
  types: DefinitionTypeDescriptor[]
  creating: boolean
  close: () => void
  onCreate: (input: NewDefinitionInput) => Promise<void>
}

function CreateDefinitionDialog({ folders, types, creating, close, onCreate }: CreateDefinitionDialogProps) {
  const { t } = useDefinitionsI18n()
  const [input, setInput] = useState<NewDefinitionInput>({ folderUuid: null, type: 'MAPPING', code: '', name: '', description: '' })
  const descriptor = types.find((type) => type.code === input.type)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (descriptor?.folderRequired && !input.folderUuid) return
    await onCreate(input)
  }
  return (
    <Dialog open title={t('newDefinition')} eyebrow={t('designControl')} closeLabel={t('close')} onClose={close} busy={creating} className="definition-dialog" backdropClassName="definition-dialog-backdrop">
        <form onSubmit={submit}>
          <div className="definition-form-grid">
            <label><span>{t('type')}</span><select value={input.type} onChange={(event) => setInput({ ...input, type: event.target.value as DefinitionType, folderUuid: null })}>{types.map((type) => <option key={type.code} value={type.code}>{t(definitionTypeKey[type.code])}</option>)}</select></label>
            <label><span>{t('folder')}</span><select required={descriptor?.folderRequired} value={input.folderUuid ?? ''} onChange={(event) => setInput({ ...input, folderUuid: event.target.value || null })}><option value="">{t('noFolder')}</option>{folders.filter((folder) => folder.status === 'AKTIF').map((folder) => <option key={folder.uuid} value={folder.uuid}>{folder.name} · {folder.code}</option>)}</select>{descriptor?.folderRequired && !input.folderUuid && <small>{t('folderRequired')}</small>}</label>
            <label><span>{t('code')}</span><input autoFocus required pattern="[A-Z][A-Z0-9_]{0,99}" placeholder="CUSTOMER_LOAD" value={input.code} onChange={(event) => setInput({ ...input, code: event.target.value.toLocaleUpperCase('en-US').replace(/[^A-Z0-9_]/g, '') })} /></label>
            <label><span>{t('name')}</span><input required value={input.name} onChange={(event) => setInput({ ...input, name: event.target.value })} /></label>
            <label className="definition-form-grid--wide"><span>{t('description')}</span><textarea rows={3} value={input.description} onChange={(event) => setInput({ ...input, description: event.target.value })} /></label>
          </div>
          <footer><button className="definition-button definition-button--quiet" type="button" onClick={close}>{t('cancel')}</button><button className="definition-button definition-button--primary" type="submit" disabled={creating || (descriptor?.folderRequired && !input.folderUuid)}>{creating ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}{creating ? t('creating') : t('createDefinition')}</button></footer>
        </form>
    </Dialog>
  )
}
