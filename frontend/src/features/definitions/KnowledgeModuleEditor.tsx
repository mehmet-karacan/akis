import { TabBar } from '../../core/ui/TabBar'
import { DataGrid } from '../../core/ui/DataGrid'
import { Select as FormSelect } from '../../core/ui/Select'
import { Button as AntActionButton } from '../../core/ui/Button'
import { Input as AntInput } from 'antd'
import { useState } from 'react'
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { useDefinitionsI18n } from './i18n'
import { KnowledgeLanguageEditor } from './KnowledgeLanguageEditor'

type RecordValue = Record<string, unknown>
const records = (value: unknown): RecordValue[] => Array.isArray(value) ? value.filter((item): item is RecordValue => !!item && typeof item === 'object' && !Array.isArray(item)) : []
export const moduleTypes = ['RKM', 'LKM', 'CKM', 'IKM', 'JKM', 'SKM', 'XKM'] as const

/** Authoring metadata only: no execution capability is implied by the editor. */
export function KnowledgeModuleEditor({ value, onChange, projectUuid }: { value: RecordValue; onChange(value: unknown): void; projectUuid?: string }) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const [tab, setTab] = useState('tasks')
  const [selected, setSelected] = useState(0)
  const tasks = records(value.tasks)
  const options = records(value.options)
  const index = Math.min(selected, Math.max(0, tasks.length - 1))
  const task = tasks[index]
  const update = (patch: RecordValue) => onChange({ ...value, ...patch })
  const updateTask = (patch: RecordValue) => update({ tasks: tasks.map((item, position) => position === index ? { ...item, ...patch } : item) })
  const move = (offset: number) => {
    const next = [...tasks]
    if (!next[index] || !next[index + offset]) return
    ;[next[index], next[index + offset]] = [next[index + offset]!, next[index]!]
    update({ tasks: next }); setSelected(index + offset)
  }
  const labels = tr
    ? ['Tersine Mühendislik', 'Yükleme', 'Veri Kontrolü', 'Entegrasyon', 'Değişiklik Yakalama', 'Servis', 'Dönüştürme']
    : ['Reverse Engineering', 'Loading', 'Data Check', 'Integration', 'Change Data Capture', 'Service', 'Transformation']
  if (value.language === 'AKIS_KM/1' && projectUuid) return <KnowledgeLanguageEditor projectUuid={projectUuid} value={value} onChange={onChange} />
  return <div className="km-editor">
    {projectUuid && tasks.length === 0 && options.length === 0 && ['LKM', 'IKM', 'CKM'].includes(String(value.kmType ?? 'IKM')) && <AntActionButton type="button" tone="secondary" onClick={() => {
      const kind = String(value.kmType ?? 'IKM')
      const steps = kind === 'LKM' ? 'ADIM HAZIRLA STAGING CREATE_WORK WORK_SOURCE_1\nADIM AKTAR STAGING TRANSFER_JDBC WORK_SOURCE_1\nADIM MUHURLE STAGING SEAL_WORK WORK_SOURCE_1' : kind === 'IKM' ? 'ADIM HEDEFE_YAZ TARGET ATOMIC_REPLACE WORK_SOURCE_1' : 'ADIM BOSLUK_KONTROL STAGING CHECK_NOT_NULL WORK_SOURCE_1'
      update({ language: 'AKIS_KM/1', source: `AKIS_KM/1\nMODUL ${kind}\n${steps}\n` })
    }}>{tr ? 'AKIŞ KM Diliyle Tanımla' : 'Use AKIŞ KM Language'}</AntActionButton>}
    <label className="km-type"><span>{tr ? 'Modül Türü' : 'Module Type'}</span><FormSelect value={String(value.kmType ?? 'IKM')} onChange={(event) => update({ kmType: event.target.value })}>{moduleTypes.map((type, i) => <option key={type} value={type}>{type} · {labels[i]}</option>)}</FormSelect></label>
    <p className="definition-help" role="note">{tr ? 'Bu ekran modül tanımını saklar. Özel modül görevlerinin çalışma motorunda yürütülmesi henüz desteklenmiyor.' : 'This editor stores module definitions. Executing custom module tasks in the runtime is not yet supported.'}</p>
    <TabBar className="procedure-command-tabs" role="tablist" aria-label={tr ? 'Modül Bölümleri' : 'Module Sections'}>{[['tasks', tr ? 'Görevler' : 'Tasks'], ['options', tr ? 'Seçenekler' : 'Options']].map(([key = '', label]) => <AntActionButton tone="ghost" type="button" role="tab" aria-selected={tab === key} key={key} onClick={() => setTab(key)}>{label}</AntActionButton>)}</TabBar>
    {tab === 'tasks' ? <section aria-label={tr ? 'Görevler' : 'Tasks'}>
      <div className="km-toolbar"><AntActionButton type="button" tone="secondary" onClick={() => { update({ tasks: [...tasks, { name: `${tr ? 'Görev' : 'Task'} ${tasks.length + 1}`, command: '' }] }); setSelected(tasks.length) }}><Plus size={16} />{tr ? 'Görev Ekle' : 'Add Task'}</AntActionButton></div>
      <div className="km-task-list">{tasks.length === 0 ? <p>{tr ? 'Henüz görev yok. İlk görevi ekleyin.' : 'No tasks yet. Add the first task.'}</p> : <DataGrid><thead><tr><th>#</th><th>{tr ? 'Görev Adı' : 'Task Name'}</th><th>{tr ? 'Komut' : 'Command'}</th></tr></thead><tbody>{tasks.map((item, position) => <tr key={position} className={position === index ? 'is-selected' : ''}><td>{position + 1}</td><td><AntActionButton tone="ghost" type="button" aria-pressed={position === index} onClick={() => setSelected(position)}>{String(item.name || (tr ? 'Adsız Görev' : 'Unnamed Task'))}</AntActionButton></td><td><code>{String(item.command ?? '').split('\n')[0]}</code></td></tr>)}</tbody></DataGrid>}</div>
      {task && <section className="km-task-detail" aria-label={tr ? 'Seçili Görev' : 'Selected Task'}><header><strong>{tr ? 'Seçili Görev' : 'Selected Task'} · {index + 1}</strong><div className="mapping-inline-actions"><AntActionButton tone="ghost" type="button" className="definition-icon-button" aria-label={tr ? 'Yukarı Taşı' : 'Move Up'} disabled={index === 0} onClick={() => move(-1)}><ArrowUp size={16} /></AntActionButton><AntActionButton tone="ghost" type="button" className="definition-icon-button" aria-label={tr ? 'Aşağı Taşı' : 'Move Down'} disabled={index === tasks.length - 1} onClick={() => move(1)}><ArrowDown size={16} /></AntActionButton><AntActionButton tone="ghost" type="button" className="definition-icon-button" aria-label={tr ? 'Görevi Sil' : 'Delete Task'} onClick={() => update({ tasks: tasks.filter((_, position) => position !== index) })}><Trash2 size={16} /></AntActionButton></div></header><label><span>{tr ? 'Görev Adı' : 'Task Name'}</span><AntInput value={String(task.name ?? '')} onChange={(event) => updateTask({ name: event.target.value })} /></label><label><span>{tr ? 'Komut' : 'Command'}</span><AntInput.TextArea spellCheck={false} value={String(task.command ?? '')} onChange={(event) => updateTask({ command: event.target.value })} /></label></section>}
    </section> : <section className="km-options"><div className="km-toolbar"><AntActionButton type="button" tone="secondary" onClick={() => update({ options: [...options, { name: '', description: '', defaultValue: '' }] })}><Plus size={16} />{tr ? 'Seçenek Ekle' : 'Add Option'}</AntActionButton></div>{options.map((option, position) => <fieldset key={position}><legend>{tr ? 'Seçenek' : 'Option'} {position + 1}</legend>{[['name', tr ? 'Ad' : 'Name'], ['description', tr ? 'Açıklama' : 'Description'], ['defaultValue', tr ? 'Varsayılan Değer' : 'Default Value']].map(([key = '', label]) => <label key={key}><span>{label}</span><AntInput value={String(option[key] ?? '')} onChange={(event) => update({ options: options.map((item, i) => i === position ? { ...item, [key]: event.target.value } : item) })} /></label>)}<AntActionButton tone="ghost" type="button" className="definition-icon-button" aria-label={`${tr ? 'Seçeneği Sil' : 'Delete Option'} ${position + 1}`} onClick={() => update({ options: options.filter((_, i) => i !== position) })}><Trash2 size={16} /></AntActionButton></fieldset>)}</section>}
  </div>
}
