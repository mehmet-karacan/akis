import {
  AlertTriangle,
  CheckCircle2,
  FileJson,
  FolderInput,
  LoaderCircle,
  RotateCcw,
  ShieldCheck,
} from 'lucide-react'
import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiProblem } from '../../core/api/client'
import { Dialog } from '../../core/ui/Dialog'
import { bundleApi } from './api'
import { useBundleI18n, type BundleMessageKey } from './i18n'
import type {
  ConflictPolicy,
  ImportResult,
  SelectedBundle,
  ValidationReport,
} from './types'
import { formatFileSize, readBundleFile } from './utils'
import './bundles.css'

type WorkingState = 'validate' | 'dryRun' | 'import' | null

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
  const validationSequence = useRef(0)
  const fileInput = useRef<HTMLInputElement>(null)
  const completionPanel = useRef<HTMLElement>(null)

  useEffect(() => {
    if (importResult) completionPanel.current?.focus()
  }, [importResult])

  const proofKey = selected ? `${selected.fingerprint}:${conflict}` : ''
  const dryRunPassed = Boolean(proofKey && proofKey === dryRunProof)

  const clearAfterSelection = () => {
    setValidation(null)
    setDryRunProof('')
    setDryRunResult(null)
    setImportResult(null)
    setImportAttempted(false)
    setConfirmOpen(false)
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

  const counts = validation?.counts ?? dryRunResult?.counts ?? emptyCounts

  return (
    <main className="bundle-page">
      <header className="bundle-page-header">
        <div>
          <span className="bundle-eyebrow">{t('eyebrow')}</span>
          <h1>{t('importTitle')}</h1>
          <p>{t('importDescription')}</p>
        </div>
        {selected || importResult ? (
          <button className="bundle-button bundle-button-secondary" type="button" onClick={reset} disabled={working !== null}>
            <RotateCcw aria-hidden="true" /> {t('reset')}
          </button>
        ) : null}
      </header>

      <div className="bundle-security-note">
        <ShieldCheck aria-hidden="true" />
        <span>{t('securityNotice')}</span>
      </div>

      <section className="bundle-panel" aria-labelledby="bundle-file-heading">
        <div className="bundle-section-heading">
          <div className="bundle-step">1</div>
          <div><h2 id="bundle-file-heading">{t('chooseFile')}</h2><p>{t('fileHelp')}</p></div>
        </div>

        <input
          ref={fileInput}
          className="bundle-visually-hidden"
          id="bundle-file-input"
          type="file"
          accept=".json,application/json"
          onChange={(event) => void selectFile(event)}
          disabled={working !== null}
        />
        <label className="bundle-file-picker" htmlFor="bundle-file-input">
          <FileJson aria-hidden="true" />
          <span>{selected ? t('replaceFile') : t('chooseFile')}</span>
        </label>

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
        <section className="bundle-panel" aria-labelledby="bundle-policy-heading">
          <div className="bundle-section-heading">
            <div className="bundle-step">2</div>
            <div><h2 id="bundle-policy-heading">{t('conflictPolicy')}</h2><p>{t('dryRunRequired')}</p></div>
          </div>
          <fieldset className="bundle-policy-options" disabled={working !== null || importAttempted || Boolean(importResult)}>
            <legend className="bundle-visually-hidden">{t('conflictPolicy')}</legend>
            <label>
              <input type="radio" name="bundle-conflict" value="FAIL" checked={conflict === 'FAIL'} onChange={() => changeConflict('FAIL')} />
              <span><strong>{t('conflictFail')}</strong><small>{t('conflictFailHelp')}</small></span>
            </label>
            <label>
              <input type="radio" name="bundle-conflict" value="RENAME" checked={conflict === 'RENAME'} onChange={() => changeConflict('RENAME')} />
              <span><strong>{t('conflictRename')}</strong><small>{t('conflictRenameHelp')}</small></span>
            </label>
          </fieldset>

          <div className="bundle-action-row">
            <button className="bundle-button bundle-button-secondary" type="button" onClick={() => void runDryRun()} disabled={working !== null || importAttempted || Boolean(importResult)}>
              {working === 'dryRun' ? <LoaderCircle className="bundle-spin" aria-hidden="true" /> : <FolderInput aria-hidden="true" />}
              {working === 'dryRun' ? t('dryRunning') : t('dryRun')}
            </button>
            <button className="bundle-button bundle-button-primary" type="button" onClick={() => setConfirmOpen(true)} disabled={!dryRunPassed || working !== null || Boolean(importResult)}>
              {working === 'import' ? <LoaderCircle className="bundle-spin" aria-hidden="true" /> : <FolderInput aria-hidden="true" />}
              {working === 'import' ? t('importing') : t('importProject')}
            </button>
          </div>
          {dryRunPassed ? <div className="bundle-alert bundle-alert-success" role="status"><CheckCircle2 aria-hidden="true" /> {t('dryRunPassed')}</div> : null}
        </section>
      ) : null}

      {error ? <div className="bundle-alert bundle-alert-error" role="alert"><AlertTriangle aria-hidden="true" /><span>{error}</span></div> : null}

      {importResult ? (
        <section ref={completionPanel} className="bundle-panel bundle-complete" aria-labelledby="bundle-complete-heading" role="status" aria-live="polite" tabIndex={-1}>
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

      {validation?.valid ? <BundleCountsView counts={counts} /> : null}
      <Dialog open={confirmOpen} title={t('confirmImportTitle')} eyebrow={t('confirmImportEyebrow')} closeLabel={t('close')} busy={working === 'import'} onClose={() => setConfirmOpen(false)} className="bundle-confirm-dialog">
        <p>{t('confirmImportDescription').replace('{{project}}', selected?.document.project.name ?? '')}</p>
        <dl className="bundle-file-summary">
          <div><dt>{t('bundleProject')}</dt><dd>{selected?.document.project.code}</dd></div>
          <div><dt>{t('conflictPolicy')}</dt><dd>{conflict === 'FAIL' ? t('conflictFail') : t('conflictRename')}</dd></div>
        </dl>
        <footer className="bundle-confirm-actions"><button className="bundle-button bundle-button-secondary" type="button" onClick={() => setConfirmOpen(false)}>{t('cancel')}</button><button className="bundle-button bundle-button-primary" type="button" onClick={() => void importProject()}>{t('confirmImport')}</button></footer>
      </Dialog>
    </main>
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
        <table className="bundle-issues-table">
          <thead><tr><th scope="col">{t('issueCode')}</th><th scope="col">{t('issuePath')}</th><th scope="col">{t('issueMessage')}</th></tr></thead>
          <tbody>{report.issues.map((issue, index) => <tr key={`${issue.code}:${issue.path}:${index}`}><td><code>{issue.code}</code></td><td><code>{issue.path}</code></td><td>{messageForCode(issue.code, issue.message, t('validationFailed'))}</td></tr>)}</tbody>
        </table>
      </div>
    </div>
  )
}

function BundleCountsView({ counts }: { counts: ImportResult['counts'] }) {
  const { t, locale } = useBundleI18n()
  const items = [
    ['folders', counts.folders],
    ['definitions', counts.definitions],
    ['drafts', counts.drafts],
    ['versions', counts.versions],
  ] as const
  return (
    <section className="bundle-counts" aria-label={t('bundleProject')}>
      {items.map(([key, value]) => <div key={key}><strong>{new Intl.NumberFormat(locale).format(value)}</strong><span>{t(key)}</span></div>)}
    </section>
  )
}
