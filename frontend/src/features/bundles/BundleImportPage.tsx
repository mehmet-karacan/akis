import { DataGrid } from '../../core/ui/DataGrid'
import { Radio as AntRadio, Select as AntSelect } from 'antd'
import { Button as AntActionButton } from '../../core/ui/Button'
import {
  AlertTriangle,
  CheckCircle2,
  FileJson,
  Folder,
  FolderInput,
  GitCommitVertical,
  Layers3,
  LoaderCircle,
  NotebookPen,
  Plus,
  RotateCcw,
  ShieldCheck,
} from 'lucide-react'
import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiProblem } from '../../core/api/client'
import { Dialog } from '../../core/ui/Dialog'
import { PageHeader, SummaryStrip } from '../../core/ui'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import { listProjects, type Project } from '../projects/projectsApi'
import { topologyApi, type Connection, type Environment, type LogicalSchema, type PhysicalSchema } from '../topology/api'
import { bundleApi } from './api'
import { useBundleI18n, type BundleMessageKey } from './i18n'
import type {
  ConflictPolicy,
  GlobalBinding,
  GlobalBindingMode,
  GlobalDependency,
  GlobalResourceType,
  ImportResult,
  ProjectBundleDocument,
  SelectedBundle,
  TargetImportPlan,
  TargetImportResult,
  ValidationReport,
} from './types'
import { formatFileSize, isV3Bundle, readBundleFile } from './utils'
import './bundles.css'

type WorkingState = 'validate' | 'dryRun' | 'import' | 'loadProjects' | 'loadTopology' | 'plan' | null

const emptyCounts = { folders: 0, definitions: 0, drafts: 0, versions: 0 }

export function BundleImportPage() {
  const { t, locale, messageForCode } = useBundleI18n()
  const [selected, setSelected] = useState<SelectedBundle | null>(null)
  const [validation, setValidation] = useState<ValidationReport | null>(null)
  const [conflict, setConflict] = useState<ConflictPolicy>('FAIL')
  const [dryRunProof, setDryRunProof] = useState('')
  const [dryRunResult, setDryRunResult] = useState<ImportResult | null>(null)
  const [importResult, setImportResult] = useState<ImportResult | null>(null)
  const [error, setError] = useState('')
  const [working, setWorking] = useState<WorkingState>(null)
  const [importAttempted, setImportAttempted] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [confirmV3Open, setConfirmV3Open] = useState(false)
  // v3 flow state
  const [projects, setProjects] = useState<Project[] | null>(null)
  const [projectsError, setProjectsError] = useState(false)
  const [targetProjectUuid, setTargetProjectUuid] = useState<string | null>(null)
  const [targetResources, setTargetResources] = useState<{
    connections: Connection[]
    physicalSchemas: PhysicalSchema[]
    logicalSchemas: LogicalSchema[]
    environments: Environment[]
  } | null>(null)
  const [bindings, setBindings] = useState<GlobalBinding[]>([])
  const [plan, setPlan] = useState<TargetImportPlan | null>(null)
  const [targetImportResult, setTargetImportResult] = useState<TargetImportResult | null>(null)
  const validationSequence = useRef(0)
  const v3ImportAttempt = useRef<{ scope: string; key: string } | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const completionPanel = useRef<HTMLElement>(null)

  useEffect(() => {
    if (importResult || targetImportResult) completionPanel.current?.focus()
  }, [importResult, targetImportResult])

  const isV3 = Boolean(selected && isV3Bundle(selected.document))
  const v3Enabled = isV3 && validation?.valid === true

  const proofKey = selected ? `${selected.fingerprint}:${conflict}` : ''
  const dryRunPassed = Boolean(proofKey && proofKey === dryRunProof)

  const clearAfterSelection = () => {
    setValidation(null)
    setDryRunProof('')
    setDryRunResult(null)
    setImportResult(null)
    setImportAttempted(false)
    setConfirmOpen(false)
    setConfirmV3Open(false)
    setError('')
    clearV3State()
  }

  const clearV3State = () => {
    v3ImportAttempt.current = null
    setProjects(null)
    setProjectsError(false)
    setTargetProjectUuid(null)
    setTargetResources(null)
    setBindings([])
    setPlan(null)
    setTargetImportResult(null)
  }

  const clearPlan = () => {
    setPlan(null)
    setTargetImportResult(null)
    setError('')
  }

  const selectFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file) return
    const sequence = ++validationSequence.current
    clearAfterSelection()
    setSelected(null)
    try {
      const nextSelected = await readBundleFile(file)
      if (sequence !== validationSequence.current) return
      setSelected(nextSelected)
      setWorking('validate')
      try {
        const report = await bundleApi.validate(nextSelected.document)
        if (sequence !== validationSequence.current) return
        setValidation(report)
      } catch (nextError) {
        if (sequence !== validationSequence.current) return
        setError(nextError instanceof ApiProblem
          ? messageForCode(nextError.code, nextError.message, t('requestFailed'))
          : t('requestFailed'))
      } finally {
        if (sequence === validationSequence.current) setWorking(null)
      }
    } catch (nextError) {
      if (sequence !== validationSequence.current) return
      const key = nextError instanceof Error ? nextError.message as BundleMessageKey : 'invalidJson'
      const known: BundleMessageKey[] = [
        'invalidExtension', 'emptyFile', 'fileTooLarge', 'invalidJson', 'invalidFormat',
      ]
      setError(t(known.includes(key) ? key : 'invalidJson'))
      setWorking(null)
    } finally {
      event.target.value = ''
    }
  }

  const changeConflict = (value: ConflictPolicy) => {
    setConflict(value)
    setDryRunProof('')
    setDryRunResult(null)
    setImportResult(null)
    setImportAttempted(false)
    setConfirmOpen(false)
    setError('')
  }

  const loadProjects = async () => {
    if (projects !== null) return
    setWorking('loadProjects')
    setProjectsError(false)
    setError('')
    try {
      const list = await listProjects()
      setProjects(list)
    } catch (nextError) {
      setProjectsError(true)
      setError(nextError instanceof ApiProblem
        ? messageForCode(nextError.code, nextError.message, t('loadProjectsFailed'))
        : t('loadProjectsFailed'))
    } finally {
      setWorking(null)
    }
  }

  const loadTargetTopology = async (uuid: string) => {
    setWorking('loadTopology')
    setTargetResources(null)
    setError('')
    try {
      const [connections, physicalSchemas, logicalSchemas, environments] = await Promise.all([
        topologyApi.listConnections(uuid),
        topologyApi.listPhysicalSchemas(uuid),
        topologyApi.listLogicalSchemas(uuid),
        topologyApi.listEnvironments(uuid),
      ])
      setTargetResources({ connections, physicalSchemas, logicalSchemas, environments })
    } catch (nextError) {
      setError(nextError instanceof ApiProblem
        ? messageForCode(nextError.code, nextError.message, t('requestFailed'))
        : t('requestFailed'))
    } finally {
      setWorking(null)
    }
  }

  const chooseTargetProject = (uuid: string | null) => {
    setTargetProjectUuid(uuid)
    clearPlan()
    if (uuid) {
      void loadTargetTopology(uuid)
    } else {
      setTargetResources(null)
      setBindings([])
    }
  }

  const bindingFor = (type: GlobalResourceType, sourceCode: string) =>
    bindings.find((b) => b.type === type && b.sourceCode === sourceCode)

  const setBindingMode = (dependency: GlobalDependency, mode: GlobalBindingMode) => {
    clearPlan()
    setBindings((prev) => {
      const next = prev.filter((b) => !(b.type === dependency.type && b.sourceCode === dependency.sourceCode))
      next.push({
        type: dependency.type,
        sourceCode: dependency.sourceCode,
        mode,
        targetUuid: null,
        newCode: null,
        configuration: null,
      })
      return next
    })
  }

  const setBindingTarget = (dependency: GlobalDependency, targetUuid: string | null) => {
    clearPlan()
    setBindings((prev) => {
      const next = prev.filter((b) => !(b.type === dependency.type && b.sourceCode === dependency.sourceCode))
      const existing = prev.find((b) => b.type === dependency.type && b.sourceCode === dependency.sourceCode)
      next.push({
        type: dependency.type,
        sourceCode: dependency.sourceCode,
        mode: existing?.mode ?? 'BIND_EXISTING',
        targetUuid,
        newCode: existing?.newCode ?? null,
        configuration: existing?.configuration ?? null,
      })
      return next
    })
  }

  const planRequestBindings = (dependencies: GlobalDependency[]): GlobalBinding[] => {
    return dependencies.map((dep) => {
      const existing = bindingFor(dep.type, dep.sourceCode)
      if (existing) return existing
      return {
        type: dep.type,
        sourceCode: dep.sourceCode,
        mode: 'BIND_EXISTING',
        targetUuid: null,
        newCode: null,
        configuration: null,
      }
    })
  }

  const runPlan = async () => {
    if (!selected || !targetProjectUuid) return
    setWorking('plan')
    setPlan(null)
    setError('')
    try {
      const currentDependencies = plan?.globalDependencies ?? []
      const requestBindings = currentDependencies.length > 0 ? planRequestBindings(currentDependencies) : bindings
      const result = await bundleApi.planTargetImport(targetProjectUuid, selected.document, requestBindings)
      setPlan(result)
      // sync bindings from the plan response so new dependencies appear mapped
      setBindings(result.globalDependencies.map((dep) => {
        const existing = bindingFor(dep.type, dep.sourceCode)
        return {
          type: dep.type,
          sourceCode: dep.sourceCode,
          mode: existing?.mode ?? dep.mode ?? 'BIND_EXISTING',
          targetUuid: existing?.targetUuid ?? dep.targetUuid ?? null,
          newCode: existing?.newCode ?? dep.targetCode ?? null,
          configuration: existing?.configuration ?? null,
        }
      }))
    } catch (nextError) {
      setError(nextError instanceof ApiProblem
        ? messageForCode(nextError.code, nextError.message, t('requestFailed'))
        : t('requestFailed'))
    } finally {
      setWorking(null)
    }
  }

  const importIntoTarget = async () => {
    if (!selected || !targetProjectUuid || !plan || !plan.valid) return
    setWorking('import')
    setConfirmOpen(false)
    setConfirmV3Open(false)
    setImportAttempted(true)
    setError('')
    try {
      const scope = `${selected.fingerprint}:${targetProjectUuid}:${plan.planDigest}:${plan.targetVersion}`
      if (v3ImportAttempt.current?.scope !== scope) {
        v3ImportAttempt.current = { scope, key: crypto.randomUUID() }
      }
      const idempotencyKey = v3ImportAttempt.current.key
      const result = await bundleApi.importIntoTarget(targetProjectUuid, {
        bundle: selected.document,
        globalBindings: bindings,
        planDigest: plan.planDigest,
        targetVersion: plan.targetVersion,
      }, idempotencyKey)
      if (!result.imported && !result.replayed) throw new Error(t('requestFailed'))
      setTargetImportResult(result)
    } catch {
      setError(t('importOutcomeUnknown'))
    } finally {
      setWorking(null)
    }
  }

  const runDryRun = async () => {
    if (!selected || !validation?.valid) return
    setWorking('dryRun')
    setDryRunProof('')
    setDryRunResult(null)
    setImportResult(null)
    setError('')
    try {
      const result = await bundleApi.importProject(selected.document, conflict, true)
      if (!result.dryRun) throw new Error(t('requestFailed'))
      setDryRunResult(result)
      setDryRunProof(proofKey)
    } catch (nextError) {
      setError(nextError instanceof ApiProblem
        ? messageForCode(nextError.code, nextError.message, t('requestFailed'))
        : t('requestFailed'))
    } finally {
      setWorking(null)
    }
  }

  const importProject = async () => {
    if (!selected || !dryRunPassed) return
    setWorking('import')
    setConfirmOpen(false)
    setImportAttempted(true)
    setDryRunProof('')
    setError('')
    try {
      const result = await bundleApi.importProject(selected.document, conflict, false)
      if (!result.imported || result.dryRun) throw new Error(t('requestFailed'))
      setImportResult(result)
    } catch {
      setError(t('importOutcomeUnknown'))
    } finally {
      setWorking(null)
    }
  }

  const reset = () => {
    validationSequence.current += 1
    setSelected(null)
    setValidation(null)
    setConflict('FAIL')
    setDryRunProof('')
    setDryRunResult(null)
    setImportResult(null)
    setImportAttempted(false)
    setConfirmOpen(false)
    setError('')
    setWorking(null)
    if (fileInput.current) fileInput.current.value = ''
  }

  const counts = validation?.counts ?? dryRunResult?.counts ?? plan?.counts ?? targetImportResult?.counts ?? emptyCounts
  const selectedTargetProject = projects?.find((project) => project.uuid === targetProjectUuid) ?? null

  const canImportV3 = Boolean(
    v3Enabled && targetProjectUuid && plan?.valid && !targetImportResult,
  )

  const importV3Disabled = Boolean(
    working === 'plan' || working === 'import' || working === 'loadTopology' || !canImportV3,
  )

  return (
    <section className="page-stack connections-page bundle-page">
      <section className="connection-management-panel">
        <PageHeader icon={<FolderInput />} eyebrow={t('eyebrow')} title={t('importTitle')} description={t('importDescription')} actions={selected || importResult ? (
          <AntActionButton tone="secondary" type="button" onClick={reset} disabled={working !== null}>
            <RotateCcw aria-hidden="true" /> {t('reset')}
          </AntActionButton>
        ) : undefined} />
        <div className="bundle-security-note">
          <ShieldCheck aria-hidden="true" />
          <span>{t('securityNotice')}</span>
        </div>
      </section>

      <SummaryStrip ariaLabel={t('bundleProject')} items={[
        { label: t('folders'), value: validation?.valid ? new Intl.NumberFormat(locale).format(counts.folders) : '—', icon: <Folder />, tone: 'neutral' },
        { label: t('definitions'), value: validation?.valid ? new Intl.NumberFormat(locale).format(counts.definitions) : '—', icon: <Layers3 />, tone: 'info' },
        { label: t('drafts'), value: validation?.valid ? new Intl.NumberFormat(locale).format(counts.drafts) : '—', icon: <NotebookPen />, tone: 'warning' },
        { label: t('versions'), value: validation?.valid ? new Intl.NumberFormat(locale).format(counts.versions) : '—', icon: <GitCommitVertical />, tone: 'success' },
      ]} />

      <section className="connection-detail-section bundle-panel" aria-labelledby="bundle-file-heading">
        <div className="bundle-section-heading">
          <div className="bundle-step">1</div>
          <div><h2 id="bundle-file-heading">{t('chooseFile')}</h2><p>{t('fileHelp')}</p></div>
        </div>

        <input
          ref={fileInput}
          className="bundle-visually-hidden"
          id="bundle-file-input"
          type="file"
          aria-label={t('chooseFile')}
          accept=".json,application/json"
          onChange={(event) => void selectFile(event)}
          disabled={working !== null}
        />
        <AntActionButton tone="secondary" type="button" onClick={() => fileInput.current?.click()} disabled={working !== null}>
          <FileJson aria-hidden="true" />
          <span>{selected ? t('replaceFile') : t('chooseFile')}</span>
        </AntActionButton>

        {selected ? (
          <dl className="bundle-file-summary">
            <div><dt>{t('fileName')}</dt><dd>{selected.fileName}</dd></div>
            <div><dt>{t('fileSize')}</dt><dd>{formatFileSize(selected.size, locale)}</dd></div>
            <div><dt>{t('bundleProject')}</dt><dd>{selected.document.project.name} <code>{selected.document.project.code}</code></dd></div>
          </dl>
        ) : null}

        {working === 'validate' ? (
          <div className="bundle-status" role="status"><LoaderCircle className="bundle-spin" aria-hidden="true" /> {t('validating')}</div>
        ) : null}
        {validation ? <ValidationSummary report={validation} /> : null}
      </section>

      {selected && validation?.valid ? (
        isV3 ? (
          <V3TargetImportSection
            t={t}
            document={selected.document}
            projects={projects}
            projectsError={projectsError}
            targetProjectUuid={targetProjectUuid}
            onChooseTarget={chooseTargetProject}
            onLoadProjects={loadProjects}
            targetResources={targetResources}
            bindings={bindings}
            plan={plan}
            working={working}
            messageForCode={messageForCode}
            onSetBindingMode={setBindingMode}
            onSetBindingTarget={setBindingTarget}
            onPreviewPlan={runPlan}
            onRequestImport={() => setConfirmV3Open(true)}
            importDisabled={importV3Disabled}
            importAttempted={importAttempted}
          />
        ) : (
        <section className="connection-detail-section bundle-panel" aria-labelledby="bundle-policy-heading">
          <div className="bundle-section-heading">
            <div className="bundle-step">2</div>
            <div><h2 id="bundle-policy-heading">{t('conflictPolicy')}</h2><p>{t('dryRunRequired')}</p></div>
          </div>
          <fieldset className="bundle-policy-options" disabled={working !== null || importAttempted || Boolean(importResult)}>
            <legend className="bundle-visually-hidden">{t('conflictPolicy')}</legend>
            <label>
              <AntRadio  name="bundle-conflict" value="FAIL" checked={conflict === 'FAIL'} onChange={() => changeConflict('FAIL')} />
              <span><strong>{t('conflictFail')}</strong><small>{t('conflictFailHelp')}</small></span>
            </label>
            <label>
              <AntRadio  name="bundle-conflict" value="RENAME" checked={conflict === 'RENAME'} onChange={() => changeConflict('RENAME')} />
              <span><strong>{t('conflictRename')}</strong><small>{t('conflictRenameHelp')}</small></span>
            </label>
          </fieldset>

          <div className="bundle-action-row">
            <AntActionButton tone="secondary" type="button" onClick={() => void runDryRun()} disabled={working !== null || importAttempted || Boolean(importResult)}>
              {working === 'dryRun' ? <LoaderCircle className="bundle-spin" aria-hidden="true" /> : <FolderInput aria-hidden="true" />}
              {working === 'dryRun' ? t('dryRunning') : t('dryRun')}
            </AntActionButton>
            <AntActionButton tone="primary" type="button" onClick={() => setConfirmOpen(true)} disabled={!dryRunPassed || working !== null || Boolean(importResult)}>
              {working === 'import' ? <LoaderCircle className="bundle-spin" aria-hidden="true" /> : <FolderInput aria-hidden="true" />}
              {working === 'import' ? t('importing') : t('importProject')}
            </AntActionButton>
          </div>
          {dryRunPassed ? <div className="bundle-alert bundle-alert-success" role="status"><CheckCircle2 aria-hidden="true" /> {t('dryRunPassed')}</div> : null}
        </section>
        )
      ) : null}

      {error ? <div className="bundle-alert bundle-alert-error" role="alert"><AlertTriangle aria-hidden="true" /><span>{error}</span></div> : null}

      {importResult ? (
        <section ref={completionPanel} className="connection-detail-section bundle-panel bundle-complete" aria-labelledby="bundle-complete-heading" role="status" aria-live="polite" tabIndex={-1}>
          <CheckCircle2 aria-hidden="true" />
          <div>
            <h2 id="bundle-complete-heading">{t('importComplete')}</h2>
            <p><code>{importResult.projectCode}</code></p>
            {importResult.projectUuid ? (
              <Link className="bundle-button bundle-button-primary" to={`/projects/${encodeURIComponent(importResult.projectUuid)}`}>
                {t('openProject')}
              </Link>
            ) : null}
          </div>
        </section>
      ) : null}

      {targetImportResult ? (
        <section ref={completionPanel} className="connection-detail-section bundle-panel bundle-complete" aria-labelledby="bundle-complete-heading" role="status" aria-live="polite" tabIndex={-1}>
          <CheckCircle2 aria-hidden="true" />
          <div>
            <h2 id="bundle-complete-heading">
              {targetImportResult.replayed ? t('targetImportReplayed') : t('targetImportComplete')}
            </h2>
            <p>{t('targetVersion')}: <code>{targetImportResult.targetVersion}</code></p>
            <p>{t('schedulesRemainSuspended')}</p>
            <Link className="bundle-button bundle-button-primary" to={`/projects/${encodeURIComponent(targetImportResult.targetProjectUuid)}`}>
              {t('targetProjectLink')}
            </Link>
          </div>
        </section>
      ) : null}

      <Dialog open={confirmOpen} title={t('confirmImportTitle')} eyebrow={t('confirmImportEyebrow')} closeLabel={t('close')} busy={working === 'import'} onClose={() => setConfirmOpen(false)} className="bundle-confirm-dialog">
        <p>{t('confirmImportDescription').replace('{{project}}', selected?.document.project.name ?? '')}</p>
        <dl className="bundle-file-summary">
          <div><dt>{t('bundleProject')}</dt><dd>{selected?.document.project.code}</dd></div>
          <div><dt>{t('conflictPolicy')}</dt><dd>{conflict === 'FAIL' ? t('conflictFail') : t('conflictRename')}</dd></div>
        </dl>
        <footer className="bundle-confirm-actions"><AntActionButton tone="secondary" type="button" onClick={() => setConfirmOpen(false)}>{t('cancel')}</AntActionButton><AntActionButton tone="primary" type="button" onClick={() => void importProject()}>{t('confirmImport')}</AntActionButton></footer>
      </Dialog>

      <Dialog open={confirmV3Open} title={t('confirmImportTargetTitle')} eyebrow={t('confirmImportTargetEyebrow')} closeLabel={t('close')} busy={working === 'import'} onClose={() => setConfirmV3Open(false)} className="bundle-confirm-dialog">
        <p>{t('confirmImportTargetDescription').replace('{{target}}', selectedTargetProject ? `${selectedTargetProject.name} (${selectedTargetProject.code})` : '')}</p>
        <dl className="bundle-file-summary">
          <div><dt>{t('targetProject')}</dt><dd>{selectedTargetProject?.name} <code>{selectedTargetProject?.code}</code></dd></div>
          <div><dt>{t('bundleProject')}</dt><dd>{selected?.document.project.code}</dd></div>
          <div><dt>{t('planDigest')}</dt><dd><code>{plan?.planDigest}</code></dd></div>
        </dl>
        <footer className="bundle-confirm-actions"><AntActionButton tone="secondary" type="button" onClick={() => setConfirmV3Open(false)} disabled={working === 'import'}>{t('cancel')}</AntActionButton><AntActionButton tone="primary" type="button" onClick={() => void importIntoTarget()} disabled={working === 'import'}>{t('confirmImportTarget')}</AntActionButton></footer>
      </Dialog>
    </section>
  )
}

function V3TargetImportSection({
  t,
  document,
  projects,
  projectsError,
  targetProjectUuid,
  onChooseTarget,
  onLoadProjects,
  targetResources,
  bindings,
  plan,
  working,
  messageForCode,
  onSetBindingMode,
  onSetBindingTarget,
  onPreviewPlan,
  onRequestImport,
  importDisabled,
  importAttempted,
}: {
  t: (key: BundleMessageKey) => string
  document: ProjectBundleDocument
  projects: Project[] | null
  projectsError: boolean
  targetProjectUuid: string | null
  onChooseTarget: (uuid: string | null) => void
  onLoadProjects: () => Promise<void>
  targetResources: {
    connections: Connection[]
    physicalSchemas: PhysicalSchema[]
    logicalSchemas: LogicalSchema[]
    environments: Environment[]
  } | null
  bindings: GlobalBinding[]
  plan: TargetImportPlan | null
  working: WorkingState
  messageForCode: (code: string | undefined, original: string, fallback: string) => string
  onSetBindingMode: (dependency: GlobalDependency, mode: GlobalBindingMode) => void
  onSetBindingTarget: (dependency: GlobalDependency, targetUuid: string | null) => void
  onPreviewPlan: () => Promise<void>
  onRequestImport: () => void
  importDisabled: boolean
  importAttempted: boolean
}) {
  const projectOptions = projects ?? []
  const dependencies = plan?.globalDependencies ?? []
  const planValid = plan?.valid ?? false

  return (
    <>
      <section className="connection-detail-section bundle-panel" aria-labelledby="bundle-source-heading">
        <div className="bundle-section-heading">
          <div className="bundle-step">2</div>
          <div>
            <h2 id="bundle-source-heading">{t('sourceBundleProject')}</h2>
            <p>{t('targetProjectEmptyNotice')}</p>
          </div>
        </div>
        <dl className="bundle-file-summary">
          <div><dt>{t('bundleProject')}</dt><dd>{document.project.name} <code>{document.project.code}</code></dd></div>
        </dl>
      </section>

      <section className="connection-detail-section bundle-panel" aria-labelledby="bundle-target-heading">
        <div className="bundle-section-heading">
          <div className="bundle-step">3</div>
          <div>
            <h2 id="bundle-target-heading">{t('targetProject')}</h2>
            <p>{t('targetProjectHelp')}</p>
          </div>
        </div>

        {projects === null && !projectsError ? (
          <AntActionButton tone="secondary" type="button" onClick={() => void onLoadProjects()} disabled={working === 'loadProjects'}>
            {working === 'loadProjects' ? <LoaderCircle className="bundle-spin" aria-hidden="true" /> : <Folder aria-hidden="true" />}
            {working === 'loadProjects' ? t('loadingProjects') : t('chooseTargetProject')}
          </AntActionButton>
        ) : null}

        {projectsError ? <div className="bundle-alert bundle-alert-error" role="alert"><AlertTriangle aria-hidden="true" /> {t('loadProjectsFailed')}</div> : null}

        {projects !== null ? (
          <>
            <label htmlFor="bundle-target-select" className="bundle-visually-hidden">{t('targetProject')}</label>
            <AntSelect
              id="bundle-target-select"
              data-testid="bundle-target-select"
              className="bundle-target-select"
              style={{ width: '100%', maxWidth: 480 }}
              placeholder={t('chooseTargetProject')}
              value={targetProjectUuid ?? undefined}
              onChange={(value: string | undefined) => onChooseTarget(value ?? null)}
              options={projectOptions.map((p) => ({ value: p.uuid, label: t('projectCodeWithName').replace('{{name}}', p.name).replace('{{code}}', p.code) }))}
              disabled={working !== null}
            />
            {projectOptions.length === 0 ? (
              <div className="bundle-alert bundle-alert-error" role="alert"><AlertTriangle aria-hidden="true" /> {t('noProjects')}</div>
            ) : null}
            <div className="bundle-create-project-link">
              <Link to="/project/select"><Plus aria-hidden="true" /> {t('createProjectLink')}</Link>
            </div>
          </>
        ) : null}

        {working === 'loadTopology' ? (
          <div className="bundle-status" role="status"><LoaderCircle className="bundle-spin" aria-hidden="true" /> {t('loadingProjects')}</div>
        ) : null}
      </section>

      {targetProjectUuid && targetResources ? (
        <section className="connection-detail-section bundle-panel" aria-labelledby="bundle-bindings-heading">
          <div className="bundle-section-heading">
            <div className="bundle-step">4</div>
            <div>
              <h2 id="bundle-bindings-heading">{t('globalResources')}</h2>
              <p>{t('globalResourcesHelp')}</p>
            </div>
          </div>

          {bindings.some((binding) => binding.mode === 'BIND_EXISTING' && binding.targetUuid
            && ['CONNECTION', 'PHYSICAL_SCHEMA', 'LOGICAL_SCHEMA'].includes(binding.type)) ? (
            <div className="bundle-alert bundle-alert-info" role="status">
              <AlertTriangle aria-hidden="true" /> {t('sharedPhysicalTargetWarning')}
            </div>
          ) : null}

          {dependencies.length === 0 && !plan ? (
            <div className="bundle-alert bundle-alert-info" role="status"><ShieldCheck aria-hidden="true" /> {t('planRequired')}</div>
          ) : null}

          {dependencies.length > 0 ? (
            <div className="bundle-table-wrap">
              <DataGrid className="bundle-issues-table bundle-bindings-table">
                <thead>
                  <tr>
                    <th scope="col">{t('dependencyType')}</th>
                    <th scope="col">{t('dependencySourceCode')}</th>
                    <th scope="col">{t('bindingMode')}</th>
                    <th scope="col">{t('targetResource')}</th>
                    <th scope="col">{t('dependencyMessage')}</th>
                  </tr>
                </thead>
                <tbody>
                  {dependencies.map((dep, index) => {
                    const binding = bindings.find((b) => b.type === dep.type && b.sourceCode === dep.sourceCode)
                    const mode = binding?.mode ?? dep.mode ?? 'BIND_EXISTING'
                    const targets = filteredTargetsFor(dep, targetResources)
                    const selectedTarget = targets.find((r) => r.uuid === binding?.targetUuid)
                    return (
                      <tr key={`${dep.type}:${dep.sourceCode}:${index}`}>
                        <td>{dep.type}</td>
                        <td><code>{dep.sourceCode}</code></td>
                        <td>
                          <fieldset className="bundle-binding-mode" disabled={working !== null}>
                            <legend className="bundle-visually-hidden">{t('bindingMode')}</legend>
                            <label>
                              <AntRadio
                                name={`binding-mode-${dep.type}-${dep.sourceCode}`}
                                value="BIND_EXISTING"
                                checked={mode === 'BIND_EXISTING'}
                                onChange={() => onSetBindingMode(dep, 'BIND_EXISTING')}
                              />
                              <span>{t('bindExisting')}</span>
                            </label>
                            <label>
                              <AntRadio
                                name={`binding-mode-${dep.type}-${dep.sourceCode}`}
                                value="CREATE_GLOBAL"
                                checked={mode === 'CREATE_GLOBAL'}
                                onChange={() => onSetBindingMode(dep, 'CREATE_GLOBAL')}
                              />
                              <span>{t('createGlobal')}</span>
                            </label>
                          </fieldset>
                          {mode === 'CREATE_GLOBAL' ? (
                            <small className="bundle-binding-honest">{t('createGlobalHonest')}</small>
                          ) : null}
                        </td>
                        <td>
                          {mode === 'BIND_EXISTING' ? (
                            <>
                              <label htmlFor={`bundle-target-${dep.type}-${dep.sourceCode}`} className="bundle-visually-hidden">{t('targetResource')}</label>
                              <AntSelect
                                id={`bundle-target-${dep.type}-${dep.sourceCode}`}
                                data-testid={`bundle-target-${dep.type}-${dep.sourceCode}`}
                                style={{ width: '100%', minWidth: 180 }}
                                placeholder={t('targetResource')}
                                value={selectedTarget?.uuid ?? undefined}
                                onChange={(value: string | undefined) => onSetBindingTarget(dep, value ?? null)}
                                options={targets.map((r) => ({ value: r.uuid, label: `${r.name} (${r.code})` }))}
                                disabled={working !== null}
                                notFoundContent={t('noMatchingTargetResource')}
                              />
                            </>
                          ) : (
                            <span className="bundle-binding-placeholder">—</span>
                          )}
                        </td>
                        <td>
                          {dep.required ? <strong>{t('dependencyRequired')}</strong> : <span>{t('dependencyOptional')}</span>}
                          <br />
                          {dep.resolved ? <span className="bundle-status-resolved">{t('dependencyResolved')}</span> : <span className="bundle-status-unresolved">{t('dependencyUnresolved')}</span>}
                          {dep.message ? <><br /><small>{dep.message}</small></> : null}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </DataGrid>
            </div>
          ) : null}

          <div className="bundle-action-row">
            <AntActionButton
              tone="secondary"
              type="button"
              onClick={() => void onPreviewPlan()}
              disabled={working === 'plan' || working === 'loadTopology' || !targetProjectUuid}
            >
              {working === 'plan' ? <LoaderCircle className="bundle-spin" aria-hidden="true" /> : <FolderInput aria-hidden="true" />}
              {working === 'plan' ? t('planning') : t('previewPlan')}
            </AntActionButton>
            <AntActionButton
              tone="primary"
              type="button"
              onClick={onRequestImport}
              disabled={importDisabled}
            >
              {working === 'import' ? <LoaderCircle className="bundle-spin" aria-hidden="true" /> : <FolderInput aria-hidden="true" />}
              {working === 'import' ? t('importingIntoTarget') : t('importIntoTarget')}
            </AntActionButton>
          </div>

          {plan ? (
            <div className={`bundle-alert ${planValid ? 'bundle-alert-success' : 'bundle-alert-error'}`} role="status">
              {planValid ? <CheckCircle2 aria-hidden="true" /> : <AlertTriangle aria-hidden="true" />}
              {planValid ? t('planValid') : t('planInvalid')}
            </div>
          ) : importAttempted ? (
            <div className="bundle-alert bundle-alert-error" role="alert"><AlertTriangle aria-hidden="true" /> {t('planRequired')}</div>
          ) : null}

          {plan ? <PlanSummary plan={plan} t={t} messageForCode={messageForCode} /> : null}
        </section>
      ) : null}
    </>
  )
}

function filteredTargetsFor(
  dependency: GlobalDependency,
  targetResources: {
    connections: Connection[]
    physicalSchemas: PhysicalSchema[]
    logicalSchemas: LogicalSchema[]
    environments: Environment[]
  },
) {
  let all: { uuid: string; code: string; name: string; provider: string | null }[]
  switch (dependency.type) {
    case 'CONNECTION':
      all = targetResources.connections.map((r) => ({ uuid: r.uuid, code: r.code, name: r.name, provider: r.databaseType }))
      break
    case 'PHYSICAL_SCHEMA':
      all = targetResources.physicalSchemas.map((r) => ({ uuid: r.uuid, code: r.code, name: r.name, provider: r.databaseType }))
      break
    case 'LOGICAL_SCHEMA':
      all = targetResources.logicalSchemas.map((r) => ({ uuid: r.uuid, code: r.code, name: r.name, provider: r.databaseType ?? null }))
      break
    case 'ENVIRONMENT':
      all = targetResources.environments.map((r) => ({ uuid: r.uuid, code: r.code, name: r.name, provider: null }))
      break
    default:
      all = []
  }
  if (!dependency.provider) return all
  return all.filter((r) => r.provider === dependency.provider)
}

function PlanSummary({
  plan,
  t,
  messageForCode,
}: {
  plan: TargetImportPlan
  t: (key: BundleMessageKey) => string
  messageForCode: (code: string | undefined, original: string, fallback: string) => string
}) {
  return (
    <div className="bundle-plan-summary">
      <h3>{t('changes')}</h3>
      {Object.keys(plan.changes).length === 0 ? <p>—</p> : (
        <ul>
          {Object.entries(plan.changes).map(([label, count]) => (
            <li key={label}>{t('changeCount').replace('{{count}}', String(count)).replace('{{label}}', label)}</li>
          ))}
        </ul>
      )}
      <h3>{t('issues')}</h3>
      {plan.issues.length === 0 ? <p>—</p> : (
        <div className="bundle-table-wrap">
          <DataGrid className="bundle-issues-table">
            <thead><tr><th scope="col">{t('issueCode')}</th><th scope="col">{t('issuePath')}</th><th scope="col">{t('issueMessage')}</th></tr></thead>
            <tbody>{plan.issues.map((issue, index) => <tr key={`${issue.code}:${issue.path}:${index}`}><td><code>{issue.code}</code></td><td><code>{issue.path}</code></td><td>{messageForCode(issue.code, issue.message, t('validationFailed'))}</td></tr>)}</tbody>
          </DataGrid>
        </div>
      )}
      <p className="bundle-plan-digest"><strong>{t('planDigest')}:</strong> <code>{plan.planDigest}</code></p>
    </div>
  )
}

function ValidationSummary({ report }: { report: ValidationReport }) {
  const { t, messageForCode } = useBundleI18n()
  if (report.valid) {
    return <div className="bundle-alert bundle-alert-success" role="status"><CheckCircle2 aria-hidden="true" /> {t('validationPassed')}</div>
  }
  return (
    <div className="bundle-validation-result" role="alert">
      <div className="bundle-alert bundle-alert-error"><AlertTriangle aria-hidden="true" /> {t('validationFailed')}</div>
      <h3>{t('issues')}</h3>
      <div className="bundle-table-wrap">
        <DataGrid className="bundle-issues-table">
          <thead><tr><th scope="col">{t('issueCode')}</th><th scope="col">{t('issuePath')}</th><th scope="col">{t('issueMessage')}</th></tr></thead>
          <tbody>{report.issues.map((issue, index) => <tr key={`${issue.code}:${issue.path}:${index}`}><td><code>{issue.code}</code></td><td><code>{issue.path}</code></td><td>{messageForCode(issue.code, issue.message, t('validationFailed'))}</td></tr>)}</tbody>
        </DataGrid>
      </div>
    </div>
  )
}
