import { Cable, Plug, CalendarPlus, Clock3, Database, FileText, Fingerprint, GitBranch, Globe2, Hash, ListOrdered, Network, Play, Server, Settings2, ShieldCheck, Type, UserPlus, UserRound, UserRoundPen } from 'lucide-react'
import { Children, isValidElement, type ReactNode } from 'react'

export function fieldText(node: ReactNode): string {
  return Children.toArray(node).map(child => isValidElement<{ children?: ReactNode }>(child) ? fieldText(child.props.children) : typeof child === 'string' || typeof child === 'number' ? String(child) : '').join(' ')
}

const meanings = [
  ['createdAt', /olu[sş]tur.*(zaman|tarih)|creat.*(at|time|date)/i, CalendarPlus],
  ['updatedAt', /g[uü]ncelle.*(zaman|tarih)|updat.*(at|time|date)|last.*update/i, Clock3],
  ['createdBy', /olu[sş]turan|created by|creator/i, UserPlus],
  ['updatedBy', /g[uü]ncelleyen|updated by|editor/i, UserRoundPen],
  ['user', /kullanıcı|username|user|initiator|ba[sş]latan/i, UserRound],
  ['logicalSchema', /mantıksal|logical/i, GitBranch],
  ['environment', /ortam|environment/i, Globe2],
  ['host', /sunucu|host|server/i, Server],
  ['port', /port/i, Network],
  ['connectionType', /bağlantı türü|connection type/i, Plug],
  ['connection', /bağlantı|connection/i, Cable],
  ['provider', /sağlayıcı|provider/i, Database],
  ['database', /[sş]ema|schema|database|servis|service|sid|tablo|table/i, Database],
  ['status', /durum|status|nullable|zorunlu/i, ShieldCheck],
  ['time', /zaman|tarih|time|date|ba[sş]langı[cç]|s[uü]re|duration/i, Clock3],
  ['action', /i[sş]lem|action/i, Settings2],
  ['command', /komut|command|sql/i, Play],
  ['count', /saya[cç]|count|satır|rows|sıra|order/i, ListOrdered],
  ['code', /kod|code|^#$|id$/i, Fingerprint],
  ['number', /boyut|size|number|say[ıi]/i, Hash],
  ['name', /ad[ıi]?$|name|nesne|object|type|t[uü]r/i, Type],
] as const

export function RecordFieldIcon({ label }: { label: ReactNode }) {
  const match = meanings.find(([, pattern]) => pattern.test(fieldText(label)))
  const Icon = match?.[2] ?? FileText
  return <Icon size={16} aria-hidden="true" data-field-icon={match?.[0] ?? 'description'} />
}
