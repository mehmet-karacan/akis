import { Button as AntActionButton } from '../../core/ui/Button'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput } from 'antd'
import {
  Background, Controls, MiniMap, ReactFlow, ReactFlowProvider, applyNodeChanges,
  type Connection as FlowConnection, type Node, type NodeChange, type ReactFlowInstance,
} from '@xyflow/react'
import { AlertCircle, AlignCenter, CheckCircle2, CirclePlay, Copy, ExternalLink, GitBranch, Plus, Trash2, Undo2, X } from 'lucide-react'
import { useEffect, useMemo, useState, type DragEvent } from 'react'
import { definitionsApi } from './api'
import { DefinitionTypeIcon } from './DefinitionTypeIcon'
import { definitionCodeLabel, definitionTypeKey, useDefinitionsI18n } from './i18n'
import {
  addPackageTransition, duplicatePackageStep, isPackageContent, nextPackageStepId,
  packageValidation, removePackageStep, withoutPackageLayout, type PackageContent,
  type PackageStep, type PackageStepType, type TransitionOutcome,
} from './packageGraph'
import type { Definition, DefinitionVersionSummary } from './types'

const allowedTypes = new Set(['MAPPING', 'PROCEDURE', 'PACKAGE', 'VARIABLE'])
const pickerTypes = ['MAPPING', 'PROCEDURE', 'PACKAGE', 'VARIABLE'] as const
const toStepType = (type: Definition['type']): PackageStepType => type === 'VARIABLE' ? 'VARIABLE_REFRESH' : type as PackageStepType
const outcomeKey = { SUCCESS: 'outcomeSUCCESS', FAILURE: 'outcomeFAILURE', TRUE: 'outcomeTRUE', FALSE: 'outcomeFALSE', ALWAYS: 'outcomeALWAYS' } as const
/** Steps reuse the project-object badge colors: variable steps show the variable mark, others their definition type. */
const stepDefinitionType = (type: PackageStepType): Definition['type'] => type.startsWith('VARIABLE') ? 'VARIABLE' : type as Definition['type']
const stepTypeKey = (type: PackageStepType) => stepDefinitionType(type).toLowerCase().replaceAll('_', '-')
const outcomeStyle = (outcome: TransitionOutcome | undefined) => outcome === 'FAILURE' || outcome === 'FALSE'
  ? { strokeDasharray: '6 4', stroke: 'var(--schema-color-danger)' }
  : outcome === 'SUCCESS' || outcome === 'TRUE' ? { stroke: 'var(--schema-color-success)' } : undefined
type PackagePosition = { x: number; y: number }

interface PackageEditorProps {
  projectUuid: string
  definitionUuid: string
  value: unknown
  onChange(value: unknown): void
  onOpenDefinition?(definitionUuid: string): void
}

function PackageEditorInner({ projectUuid, definitionUuid, value, onChange, onOpenDefinition }: PackageEditorProps) {
  const { language, t } = useDefinitionsI18n()
  const rawContent = isPackageContent(value) ? value : { firstStepId: '', steps: [], transitions: [] } satisfies PackageContent
  const content = withoutPackageLayout(rawContent)
  const storageKey = `akis:package-layout:v2:${projectUuid}:${definitionUuid}`
  const [definitions, setDefinitions] = useState<Definition[]>([])
  const [versionSummaries, setVersionSummaries] = useState<Record<string, DefinitionVersionSummary>>({})
  const [selectedStepId, setSelectedStepId] = useState(content.firstStepId)
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerPosition, setPickerPosition] = useState<PackagePosition | undefined>()
  const [instance, setInstance] = useState<ReactFlowInstance | null>(null)
  const [positions, setPositions] = useState<Record<string, PackagePosition>>({})
  const [undoPositions, setUndoPositions] = useState<Record<string, PackagePosition> | null>(null)
  const [undoValue, setUndoValue] = useState<PackageContent | null>(null)
  const [pendingDelete, setPendingDelete] = useState(false)
  const [transitionTarget, setTransitionTarget] = useState('')
  const [transitionOutcome, setTransitionOutcome] = useState<TransitionOutcome>('SUCCESS')
  const [message, setMessage] = useState('')

  useEffect(() => {
    try {
      const stored = localStorage.getItem(storageKey)
      const legacy = Object.fromEntries(rawContent.steps.filter((step) => step.x != null && step.y != null).map((step) => [step.id, { x: step.x as number, y: step.y as number }]))
      setPositions(stored ? JSON.parse(stored) as Record<string, PackagePosition> : legacy)
    } catch { setPositions({}) }
    setUndoPositions(null)
  }, [storageKey])

  useEffect(() => {
    let active = true
    void Promise.all([definitionsApi.listDefinitions(projectUuid), definitionsApi.listDefinitionVersionSummaries(projectUuid)]).then(([items, summaries]) => {
      if (!active) return
      const allowed = items.filter((item) => !['PASIF', 'ARSIVLENDI'].includes(item.status) && allowedTypes.has(item.type) && item.uuid !== definitionUuid)
      setDefinitions(allowed)
      setVersionSummaries(Object.fromEntries(summaries.map(summary => [summary.definitionUuid, summary])))
    }).catch(() => { if (active) { setDefinitions([]); setVersionSummaries({}) } })
    return () => { active = false }
  }, [definitionUuid, projectUuid])

  useEffect(() => {
    if (!content.steps.some((step) => step.id === selectedStepId)) {
      setSelectedStepId(content.firstStepId || content.steps[0]?.id || '')
      setInspectorOpen(false)
    }
  }, [content.firstStepId, content.steps, selectedStepId])

  const nodes: Node[] = useMemo(() => content.steps.map((step, index) => ({
    id: step.id,
    position: positions[step.id] ?? { x: (index % 10) * 250, y: Math.floor(index / 10) * 170 },
    className: `package-node package-node--${stepTypeKey(step.type)}${content.firstStepId === step.id ? ' is-start' : ''}`,
    data: { label: <div className="package-node-label"><DefinitionTypeIcon type={stepDefinitionType(step.type)} size={14} /><div className="package-node-text"><span className={content.firstStepId === step.id ? 'package-start-badge' : ''}>{content.firstStepId === step.id ? <><CirclePlay />{t('firstStep')}</> : definitionCodeLabel(step.type, language)}</span><strong title={step.name || step.id}>{step.name || step.id}</strong><small title={definitions.find((item) => item.uuid === step.definitionUuid)?.name ?? t('unlinkedStep')}>{definitions.find((item) => item.uuid === step.definitionUuid)?.name ?? t('unlinkedStep')}</small><div className="package-node-meta"><span>{versionSummaries[step.definitionUuid ?? '']?.latestVersionNumber ? `v${versionSummaries[step.definitionUuid ?? '']?.latestVersionNumber}` : '—'}</span><span>{content.transitions.filter(edge => edge.fromStepId === step.id).length} {language === 'tr' ? 'çıkış' : 'out'}</span></div></div></div> },
    selected: step.id === selectedStepId,
  })), [content.firstStepId, content.steps, content.transitions, definitions, language, positions, selectedStepId, t, versionSummaries])
  useEffect(() => {
    if (!instance || nodes.length === 0) return
    const frame = window.requestAnimationFrame(() => { void instance.fitView({ padding: .18 }) })
    return () => window.cancelAnimationFrame(frame)
  }, [instance, nodes.length])
  const edges = useMemo(() => content.transitions.map((edge, index) => ({
    id: `edge-${index}-${edge.fromStepId}-${edge.toStepId}`,
    source: edge.fromStepId,
    target: edge.toStepId,
    label: t(outcomeKey[edge.outcome ?? 'ALWAYS']),
    animated: false,
    className: `package-edge package-edge--${(edge.outcome ?? 'ALWAYS').toLowerCase()}`,
    style: outcomeStyle(edge.outcome),
  })), [content.transitions, t])

  const persistPositions = (next: Record<string, PackagePosition>) => {
    setPositions(next)
    try { localStorage.setItem(storageKey, JSON.stringify(next)) } catch { /* Layout persistence is optional. */ }
  }
  const commit = (next: PackageContent, undo = true) => {
    if (undo) setUndoValue(structuredClone(content))
    setUndoPositions(null)
    onChange(withoutPackageLayout(next))
  }
  const addStep = (linkedDefinitionUuid: string, position?: PackagePosition) => {
    const definition = definitions.find((item) => item.uuid === linkedDefinitionUuid)
    if (!definition) return
    const id = nextPackageStepId(content.steps)
    const step: PackageStep = { id, type: toStepType(definition.type), name: definition.name, definitionUuid: linkedDefinitionUuid }
    commit({ ...content, firstStepId: content.firstStepId || id, steps: [...content.steps, step] })
    if (position) persistPositions({ ...positions, [id]: position })
    setSelectedStepId(id)
    setInspectorOpen(true)
    setPickerOpen(false)
  }
  const connect = (connection: FlowConnection) => {
    if (!connection.source || !connection.target) return
    const outcomes: TransitionOutcome[] = content.steps.find((item) => item.id === connection.source)?.type === 'VARIABLE_EVALUATE' ? ['TRUE', 'FALSE'] : ['SUCCESS', 'FAILURE']
    const outcome = outcomes.find((candidate) => !content.transitions.some((edge) => edge.fromStepId === connection.source && edge.outcome === candidate))
    if (!outcome) { setMessage(t('transitionSlotUsed')); return }
    const next = addPackageTransition(content, { fromStepId: connection.source, toStepId: connection.target, outcome })
    if (!next) { setMessage(t('invalidTransition')); return }
    commit(next)
    setMessage('')
  }
  const nodeChanges = (changes: NodeChange[]) => {
    if (!changes.some((change) => change.type === 'position')) return
    const moved = applyNodeChanges(changes, nodes)
    persistPositions(Object.fromEntries(moved.map((node) => [node.id, node.position])))
  }
  const selected = content.steps.find((step) => step.id === selectedStepId)
  const validation = packageValidation(content)
  const transitionCount = selected ? content.transitions.filter((edge) => edge.fromStepId === selected.id || edge.toStepId === selected.id).length : 0
  const autoLayout = () => {
    setUndoPositions(structuredClone(positions))
    persistPositions(Object.fromEntries(content.steps.map((step, index) => [step.id, { x: (index % 10) * 250, y: Math.floor(index / 10) * 170 }])))
  }
  const drop = (event: DragEvent) => {
    event.preventDefault()
    const uuid = event.dataTransfer.getData('application/akis-definition')
    if (uuid) addStep(uuid, instance?.screenToFlowPosition({ x: event.clientX, y: event.clientY }))
  }
  const addTransition = () => {
    if (!selected || !transitionTarget) return
    const next = addPackageTransition(content, { fromStepId: selected.id, toStepId: transitionTarget, outcome: transitionOutcome })
    if (!next) { setMessage(t('invalidTransition')); return }
    commit(next)
    setMessage('')
  }
  const openLinkedDefinition = (stepId: string) => {
    const linkedUuid = content.steps.find((step) => step.id === stepId)?.definitionUuid
    if (linkedUuid) onOpenDefinition?.(linkedUuid)
  }
  const openPicker = (position?: PackagePosition) => {
    setPickerPosition(position)
    setPickerOpen(true)
    setInspectorOpen(false)
  }
  const openPickerFromCanvas = (event: { preventDefault(): void; clientX: number; clientY: number; target: EventTarget | null }) => {
    if ((event.target as HTMLElement).closest('.react-flow__node')) return
    event.preventDefault()
    openPicker(instance?.screenToFlowPosition({ x: event.clientX, y: event.clientY }))
  }
  const pickerDefinitions = definitions

  return <div className="package-editor">
    <div className="package-toolbar">
      <AntActionButton tone="secondary" type="button" disabled={!undoValue && !undoPositions} onClick={() => { if (undoPositions) { persistPositions(undoPositions); setUndoPositions(null) } else if (undoValue) { onChange(undoValue); setUndoValue(null) } }}><Undo2 size={15} />{t('undo')}</AntActionButton>
      <AntActionButton tone="secondary" type="button" onClick={autoLayout}><AlignCenter size={15} />{t('autoLayout')}</AntActionButton>
      <AntActionButton tone="primary" type="button" onClick={() => openPicker()}><Plus size={15} />{t('addObject')}</AntActionButton>
      <span className={`package-validation-chip ${validation.length ? 'is-invalid' : 'is-valid'}`} role="status">{validation.length ? <AlertCircle size={14} aria-hidden="true" /> : <CheckCircle2 size={14} aria-hidden="true" />}{validation.length ? t('validationErrors', { count: validation.length }) : t('designValid')}</span>
    </div>
    <div className="package-workbench">
        <section className="package-canvas" onDragOver={(event) => event.preventDefault()} onDrop={drop}><ReactFlow nodes={nodes} edges={edges} onInit={setInstance} onNodesChange={nodeChanges} onNodeDragStart={() => setUndoPositions(structuredClone(positions))} onNodeClick={(_, node) => { setSelectedStepId(node.id); setInspectorOpen(true); setPickerOpen(false) }} onNodeDoubleClick={(_, node) => openLinkedDefinition(node.id)} onPaneClick={() => { setInspectorOpen(false); setPickerOpen(false) }} onPaneContextMenu={openPickerFromCanvas} onConnect={connect} deleteKeyCode={null} minZoom={.25} maxZoom={2} fitView><MiniMap pannable zoomable /><Controls /><Background /></ReactFlow></section>
        {pickerOpen ? <aside className="package-component-picker" aria-label={t('addObject')}>
          <header><strong className="package-picker-title"><span className="procedure-heading-icon procedure-heading-icon--steps" aria-hidden="true"><Plus size={16} /></span>{t('addObject')}</strong><AntActionButton tone="ghost" className="definition-icon-button" type="button" aria-label={t('close')} onClick={() => setPickerOpen(false)}><X size={16} /></AntActionButton></header>
          <div className="package-picker-list">{pickerTypes.map((type) => { const items = pickerDefinitions.filter((item) => item.type === type); return items.length > 0 ? <section key={type}><h4><DefinitionTypeIcon type={type} size={13} />{t(definitionTypeKey[type])}<small className="procedure-heading-count">{items.length}</small></h4>{items.map((item) => <AntActionButton tone="ghost" key={item.uuid} type="button" className="package-picker-item" onClick={() => addStep(item.uuid, pickerPosition)}><DefinitionTypeIcon type={type} size={12} /><span>{item.name}</span><small>{item.code}</small></AntActionButton>)}</section> : null })}{pickerDefinitions.length === 0 ? <p>{t('noProjectObjects')}</p> : null}</div>
        </aside> : null}
        {inspectorOpen && selected ? <aside className="package-properties" aria-label={t('details')}>
          <header className="package-properties-header"><span className="package-properties-identity"><DefinitionTypeIcon type={stepDefinitionType(selected.type)} size={15} /><span><small>{definitionCodeLabel(selected.type, language)}</small><strong>{selected.name || selected.id}</strong></span></span><AntActionButton tone="ghost" className="definition-icon-button" type="button" aria-label={t('close')} onClick={() => setInspectorOpen(false)}><X size={16} /></AntActionButton></header>
          {message ? <p className="definition-notice definition-notice--error" role="alert">{message}</p> : null}
          <label><span>{t('stepName')}</span><AntInput value={selected.name ?? ''} onChange={(event) => onChange({ ...content, steps: content.steps.map((step) => step.id === selected.id ? { ...step, name: event.target.value } : step) })} /></label>
          <div className="package-properties-actions">
            {selected.definitionUuid && onOpenDefinition ? <AntActionButton tone="secondary" type="button" onClick={() => openLinkedDefinition(selected.id)}><ExternalLink size={15} />{t('openDefinition')}</AntActionButton> : null}
            <AntActionButton tone="secondary" type="button" disabled={content.firstStepId === selected.id} onClick={() => commit({ ...content, firstStepId: selected.id })}><CirclePlay size={15} />{t('makeStartStep')}</AntActionButton>
            <AntActionButton tone="secondary" type="button" onClick={() => commit(duplicatePackageStep(content, selected.id))}><Copy size={15} />{t('duplicate')}</AntActionButton>
            <AntActionButton tone="danger" type="button" onClick={() => setPendingDelete(true)}><Trash2 size={15} />{t('removeFromPackage')}</AntActionButton>
          </div>
          {pendingDelete ? <div className="package-delete-confirm definition-notice definition-notice--error"><p>{t('packageDeleteImpact', { count: transitionCount })}</p><AntActionButton tone="danger" type="button" onClick={() => { commit(removePackageStep(content, selected.id)); setPendingDelete(false); setInspectorOpen(false) }}>{t('confirmRemove')}</AntActionButton><AntActionButton tone="ghost" type="button" onClick={() => setPendingDelete(false)}>{t('cancel')}</AntActionButton></div> : null}
          <section><h4><GitBranch size={15} aria-hidden="true" className="package-transition-icon" />{t('addTransition')}</h4><label><span>{t('outcome')}</span><FormSelect value={transitionOutcome} onChange={(event) => setTransitionOutcome(event.target.value as TransitionOutcome)}>{(selected.type === 'VARIABLE_EVALUATE' ? ['TRUE', 'FALSE'] as const : ['SUCCESS', 'FAILURE'] as const).map((outcome) => <option key={outcome} value={outcome}>{t(outcomeKey[outcome])}</option>)}</FormSelect></label><label><span>{t('targetStep')}</span><FormSelect value={transitionTarget} onChange={(event) => setTransitionTarget(event.target.value)}><option value="">{t('notSelected')}</option>{content.steps.filter((step) => step.id !== selected.id).map((step) => <option key={step.id} value={step.id}>{step.name || step.id}</option>)}</FormSelect></label><AntActionButton tone="primary" type="button" disabled={!transitionTarget} onClick={addTransition}><GitBranch size={15} />{t('addTransition')}</AntActionButton></section>
        </aside> : null}
    </div>
  </div>
}

export function PackageEditor(props: PackageEditorProps) {
  return <ReactFlowProvider><PackageEditorInner {...props} /></ReactFlowProvider>
}
