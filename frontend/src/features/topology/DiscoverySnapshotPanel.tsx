import { useEffect, useId, useMemo, useState } from 'react'
import { Check, CheckCircle2, Clipboard, DatabaseZap, LoaderCircle, LockKeyhole, X } from 'lucide-react'
import {
  topologyApi,
  type DataObject,
  type DiscoveryResult,
  type DiscoveryTable,
  type Model,
  type SchemaBinding,
  type SchemaSnapshot,
} from './api'
import { abbreviateFingerprint } from './ConnectionVersionLifecyclePanel'
import type { getTopologyCopy } from './copy'

interface Props {
  projectUuid: string
  connectionUuid: string
  connectionLabel: string
  connectionVersionUuid: string
  connectionVersionLabel: string
  physicalSchemaUuid: string
  physicalSchemaLabel: string
  discovery: DiscoveryResult
  table: DiscoveryTable
  models: Model[]
  bindings: SchemaBinding[]
  preferredModelUuid?: string
  copy: ReturnType<typeof getTopologyCopy>
  locale: string
}

function active(status: string) {
  return status === 'AKTIF' || status === 'ACTIVE'
}

function normalizedReference(value: string) {
  return value.replaceAll('"', '').trim().toLocaleUpperCase('en-US')
}

export function exactObjectReference(table: DiscoveryTable, objectReference: string) {
  return normalizedReference(objectReference) === normalizedReference(table.name)
}

export function compatibleModels(
  models: Model[],
  bindings: SchemaBinding[],
  physicalSchemaUuid: string,
  connectionVersionUuid: string,
) {
  const logicalSchemaUuids = new Set(
    bindings
      .filter((binding) => binding.physicalSchemaUuid === physicalSchemaUuid
        && binding.connectionVersionUuid === connectionVersionUuid
        && active(binding.status))
      .map((binding) => binding.logicalSchemaUuid),
  )
  return models.filter((model) => active(model.status) && logicalSchemaUuids.has(model.logicalSchemaUuid))
}

export function compatibleDataObjects(objects: DataObject[]) {
  return objects.filter((object) => active(object.status) && object.type === 'TABLO')
}

export function DiscoverySnapshotPanel({
  projectUuid,
  connectionUuid,
  connectionLabel,
  connectionVersionUuid,
  connectionVersionLabel,
  physicalSchemaUuid,
  physicalSchemaLabel,
  discovery,
  table,
  models,
  bindings,
  preferredModelUuid,
  copy: c,
  locale,
}: Props) {
  const panelId = useId()
  const candidates = useMemo(
    () => compatibleModels(models, bindings, physicalSchemaUuid, connectionVersionUuid),
    [bindings, connectionVersionUuid, models, physicalSchemaUuid],
  )
  const defaultModelUuid = candidates.some((model) => model.uuid === preferredModelUuid)
    ? preferredModelUuid ?? ''
    : candidates[0]?.uuid ?? ''
  const [open, setOpen] = useState(false)
  const [modelUuid, setModelUuid] = useState(defaultModelUuid)
  const [objects, setObjects] = useState<DataObject[]>([])
  const [dataObjectUuid, setDataObjectUuid] = useState('')
  const [loadingObjects, setLoadingObjects] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [snapshot, setSnapshot] = useState<SchemaSnapshot | null>(null)
  const [copied, setCopied] = useState(false)
  const formatter = useMemo(
    () => new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }),
    [locale],
  )

  useEffect(() => {
    if (!open || !modelUuid || snapshot) return
    let current = true
    setLoadingObjects(true)
    setObjects([])
    setDataObjectUuid('')
    setError('')
    topologyApi.listDataObjects(projectUuid, modelUuid)
      .then((items) => {
        if (!current) return
        const compatible = compatibleDataObjects(items)
        setObjects(compatible)
        setDataObjectUuid(
          compatible.find((object) => exactObjectReference(table, object.objectReference))?.uuid
            ?? compatible[0]?.uuid
            ?? '',
        )
      })
      .catch(() => { if (current) setError(c.snapshotObjectsLoadFailed) })
      .finally(() => { if (current) setLoadingObjects(false) })
    return () => { current = false }
  }, [c.snapshotObjectsLoadFailed, modelUuid, open, projectUuid, snapshot, table])

  const save = async () => {
    if (!dataObjectUuid || saving) return
    setSaving(true)
    setError('')
    try {
      setSnapshot(await topologyApi.captureOracleSchemaSnapshot(
        projectUuid,
        connectionUuid,
        connectionVersionUuid,
        physicalSchemaUuid,
        dataObjectUuid,
      ))
    } catch {
      setError(c.snapshotSaveFailed)
    } finally {
      setSaving(false)
    }
  }

  const copyFingerprint = async () => {
    if (!snapshot) return
    try {
      await navigator.clipboard.writeText(snapshot.fingerprint)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  const selectedObject = objects.find((object) => object.uuid === dataObjectUuid)

  if (!open) {
    return <div className="topology-snapshot-entry">
      <button className="topology-button topology-button--quiet topology-button--small" type="button" aria-expanded="false" aria-controls={panelId} onClick={() => setOpen(true)}>
        <DatabaseZap aria-hidden="true" />{c.saveSchemaSnapshot}
      </button>
    </div>
  }

  return <section className="topology-snapshot-panel" id={panelId} aria-label={`${c.schemaSnapshot}: ${table.owner}.${table.name}`}>
    <header>
      <div><DatabaseZap aria-hidden="true" /><div><strong>{c.saveSchemaSnapshot}</strong><span>{table.owner}.{table.name}</span></div></div>
      {!snapshot && <button className="topology-icon-button" type="button" aria-label={c.close} onClick={() => setOpen(false)} disabled={saving}><X aria-hidden="true" /></button>}
    </header>

    <dl className="topology-snapshot-evidence">
      <div><dt>{c.connection}</dt><dd>{connectionLabel}</dd></div>
      <div><dt>{c.version}</dt><dd>{connectionVersionLabel}</dd></div>
      <div><dt>{c.physicalSchema}</dt><dd>{physicalSchemaLabel}</dd></div>
      <div><dt>{c.discoveredAt}</dt><dd><time dateTime={discovery.discoveredAt}>{formatter.format(new Date(discovery.discoveredAt))}</time></dd></div>
      <div><dt>{c.columns}</dt><dd>{table.columns.length}</dd></div>
      <div><dt>{c.constraints}</dt><dd>{table.constraints.length}</dd></div>
    </dl>

    {snapshot ? <div className="topology-snapshot-success" role="status" aria-live="polite">
      <CheckCircle2 aria-hidden="true" />
      <div>
        <strong>{c.snapshotSaved}</strong>
        <span>{c.snapshotId}: <code>{snapshot.uuid}</code></span>
        <span>{c.databaseEngine}: {snapshot.engineVersion}</span>
        <span>{c.targetFingerprint}: <code title={snapshot.fingerprint}>{abbreviateFingerprint(snapshot.fingerprint)}</code>
          <button className="topology-icon-button" type="button" aria-label={copied ? c.fingerprintCopied : c.copyFingerprint} onClick={() => void copyFingerprint()}>
            {copied ? <Check aria-hidden="true" /> : <Clipboard aria-hidden="true" />}
          </button>
        </span>
        <span>{c.createdAt}: <time dateTime={snapshot.createdAt}>{formatter.format(new Date(snapshot.createdAt))}</time></span>
        <span className="sr-only" aria-live="polite">{copied ? c.fingerprintCopied : ''}</span>
      </div>
    </div> : <>
      <div className="topology-snapshot-selectors">
        <label><span>{c.model}</span><select value={modelUuid} onChange={(event) => setModelUuid(event.target.value)} disabled={saving || candidates.length === 0}>
          {candidates.length === 0 && <option value="">—</option>}
          {candidates.map((model) => <option key={model.uuid} value={model.uuid}>{model.name} · {model.code}</option>)}
        </select></label>
        <label><span>{c.dataObject}</span><select value={dataObjectUuid} onChange={(event) => setDataObjectUuid(event.target.value)} disabled={saving || loadingObjects || objects.length === 0}>
          {objects.length === 0 && <option value="">—</option>}
          {objects.map((object) => <option key={object.uuid} value={object.uuid}>
            {object.name} · {object.objectReference}{exactObjectReference(table, object.objectReference) ? ` · ${c.recommended}` : ''}
          </option>)}
        </select></label>
      </div>
      {selectedObject && !exactObjectReference(table, selectedObject.objectReference) && <p className="topology-snapshot-warning" role="status">{c.snapshotReferenceMismatch}</p>}
      {loadingObjects && <div className="topology-loading topology-loading--compact" role="status"><LoaderCircle className="is-spinning" aria-hidden="true" />{c.loadingDataObjects}</div>}
      {!loadingObjects && candidates.length === 0 && <p className="topology-muted">{c.noCompatibleModels}</p>}
      {!loadingObjects && candidates.length > 0 && objects.length === 0 && <p className="topology-muted">{c.noCompatibleDataObjects}</p>}
      {error && <div className="topology-inline-error" role="alert">{error}</div>}
      <p className="topology-managed-note"><LockKeyhole aria-hidden="true" /><span>{c.immutableSnapshotHint}</span></p>
      <div className="topology-snapshot-actions">
        <button className="topology-button topology-button--quiet" type="button" onClick={() => setOpen(false)} disabled={saving}>{c.cancel}</button>
        <button className="topology-button" type="button" onClick={() => void save()} disabled={!dataObjectUuid || saving || loadingObjects}>
          {saving ? <LoaderCircle className="is-spinning" aria-hidden="true" /> : <DatabaseZap aria-hidden="true" />}
          {saving ? c.savingSnapshot : c.confirmSnapshot}
        </button>
      </div>
    </>}
  </section>
}
