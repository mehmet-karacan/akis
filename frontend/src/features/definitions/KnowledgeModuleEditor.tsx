import { useState } from 'react'
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { useDefinitionsI18n } from './i18n'

type RecordValue = Record<string, unknown>
const records = (value: unknown): RecordValue[] => Array.isArray(value) ? value.filter((item): item is RecordValue => !!item && typeof item === 'object' && !Array.isArray(item)) : []
export const moduleTypes = ['RKM', 'LKM', 'CKM', 'IKM', 'JKM', 'SKM', 'XKM'] as const

/** Authoring metadata only: no execution capability is implied by the editor. */
export function KnowledgeModuleEditor({ value, onChange }: { value: RecordValue; onChange(value: unknown): void }) {
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
  return <div className="km-editor">
    <label className="km-type"><span>{tr ? 'Modül Türü' : 'Module Type'}</span><select value={String(value.kmType ?? 'IKM')} onChange={(event) => update({ kmType: event.target.value })}>{moduleTypes.map((type, i) => <option key={type} value={type}>{type} · {labels[i]}</option>)}</select></label>
    <p className="definition-help" role="note">{tr ? 'Bu ekran modül tanımını saklar. Özel modül görevlerinin çalışma motorunda yürütülmesi henüz desteklenmiyor.' : 'This editor stores module definitions. Executing custom module tasks in the runtime is not yet supported.'}</p>
    <div className="procedure-command-tabs" role="tablist" aria-label={tr ? 'Modül Bölümleri' : 'Module Sections'}>{[['tasks', tr ? 'Görevler' : 'Tasks'], ['options', tr ? 'Seçenekler' : 'Options']].map(([key = '', label]) => <button type="button" role="tab" aria-selected={tab === key} key={key} onClick={() => setTab(key)}>{label}</button>)}</div>
    {tab === 'tasks' ? <section aria-label={tr ? 'Görevler' : 'Tasks'}>
      <div className="km-toolbar"><button type="button" className="definition-button" onClick={() => { update({ tasks: [...tasks, { name: `${tr ? 'Görev' : 'Task'} ${tasks.length + 1}`, command: '' }] }); setSelected(tasks.length) }}><Plus size={16} />{tr ? 'Görev Ekle' : 'Add Task'}</button></div>
      <div className="km-task-list">{tasks.length === 0 ? <p>{tr ? 'Henüz görev yok. İlk görevi ekleyin.' : 'No tasks yet. Add the first task.'}</p> : <table><thead><tr><th>#</th><th>{tr ? 'Görev Adı' : 'Task Name'}</th><th>{tr ? 'Komut' : 'Command'}</th></tr></thead><tbody>{tasks.map((item, position) => <tr key={position} className={position === index ? 'is-selected' : ''}><td>{position + 1}</td><td><button type="button" aria-pressed={position === index} onClick={() => setSelected(position)}>{String(item.name || (tr ? 'Adsız Görev' : 'Unnamed Task'))}</button></td><td><code>{String(item.command ?? '').split('\n')[0]}</code></td></tr>)}</tbody></table>}</div>
      {task && <section className="km-task-detail" aria-label={tr ? 'Seçili Görev' : 'Selected Task'}><header><strong>{tr ? 'Seçili Görev' : 'Selected Task'} · {index + 1}</strong><div className="mapping-inline-actions"><button type="button" className="definition-icon-button" aria-label={tr ? 'Yukarı Taşı' : 'Move Up'} disabled={index === 0} onClick={() => move(-1)}><ArrowUp size={16} /></button><button type="button" className="definition-icon-button" aria-label={tr ? 'Aşağı Taşı' : 'Move Down'} disabled={index === tasks.length - 1} onClick={() => move(1)}><ArrowDown size={16} /></button><button type="button" className="definition-icon-button" aria-label={tr ? 'Görevi Sil' : 'Delete Task'} onClick={() => update({ tasks: tasks.filter((_, position) => position !== index) })}><Trash2 size={16} /></button></div></header><label><span>{tr ? 'Görev Adı' : 'Task Name'}</span><input value={String(task.name ?? '')} onChange={(event) => updateTask({ name: event.target.value })} /></label><label><span>{tr ? 'Komut' : 'Command'}</span><textarea spellCheck={false} value={String(task.command ?? '')} onChange={(event) => updateTask({ command: event.target.value })} /></label></section>}
    </section> : <section className="km-options"><div className="km-toolbar"><button type="button" className="definition-button" onClick={() => update({ options: [...options, { name: '', description: '', defaultValue: '' }] })}><Plus size={16} />{tr ? 'Seçenek Ekle' : 'Add Option'}</button></div>{options.map((option, position) => <fieldset key={position}><legend>{tr ? 'Seçenek' : 'Option'} {position + 1}</legend>{[['name', tr ? 'Ad' : 'Name'], ['description', tr ? 'Açıklama' : 'Description'], ['defaultValue', tr ? 'Varsayılan Değer' : 'Default Value']].map(([key = '', label]) => <label key={key}><span>{label}</span><input value={String(option[key] ?? '')} onChange={(event) => update({ options: options.map((item, i) => i === position ? { ...item, [key]: event.target.value } : item) })} /></label>)}<button type="button" className="definition-icon-button" aria-label={`${tr ? 'Seçeneği Sil' : 'Delete Option'} ${position + 1}`} onClick={() => update({ options: options.filter((_, i) => i !== position) })}><Trash2 size={16} /></button></fieldset>)}</section>}
  </div>
}
