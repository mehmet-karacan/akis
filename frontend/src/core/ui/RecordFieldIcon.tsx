import { Cable, Plug, CalendarPlus, Clock3, Database, FileText, Fingerprint, GitBranch, Globe2, Hash, ListOrdered, Network, Play, Server, Settings2, ShieldCheck, Type, UserPlus, UserRound, UserRoundPen, Table2, Columns3, KeyRound, ListTree, Share2, ClipboardList, ShieldAlert, Link2, Star, Mail, Folder, ScanSearch, Rocket, Layers3, Timer, CalendarCheck, Cpu, PencilLine, Trash2, Zap } from 'lucide-react'
import { Children, isValidElement, type ReactNode } from 'react'

export function fieldText(node: ReactNode): string {
  return Children.toArray(node).map(child => isValidElement<{ children?: ReactNode }>(child) ? fieldText(child.props.children) : typeof child === 'string' || typeof child === 'number' ? String(child) : '').join(' ')
}

const meanings = [
  ['table', /^table$|^tablo$|table catalog|tablo kataloğu|data store/i, Table2],
  ['columns', /^columns?$|^column count$|^kolon/i, Columns3],
  ['constraints', /^constraints?$|^constraint count$|^k[iı]s[iı]t/i, KeyRound],
  ['indexes', /^indexes?$|^index count$|^[İI]ndeks/i, ListTree],
  ['relationships', /^relationships?$|^relationship count$|^[İI]li[sş]ki/i, Share2],
  ['sequences', /sequence|sıra/i, ListOrdered],
  ['recordInformation', /^record information$|^kayıt bilgileri$/i, ClipboardList],
  ['createdAt', /olu[sş]tur.*(zaman|tarih)|eklenme|creat.*(at|time|date)|^created$|^oluşturulma$/i, CalendarPlus],
  ['publishedAt', /yayınlanma|published/i, CalendarCheck],
  ['risk', /^risk|risk sınıfı|risk class/i, ShieldAlert],
  ['mapping', /eşleme|mapping|binding/i, Link2],
  ['default', /varsayılan|default/i, Star],
  ['email', /e-?posta|e-?mail/i, Mail],
  ['issuer', /sağlayıcı kimliği|issuer/i, KeyRound],
  ['subject', /^konu$|^subject$/i, Fingerprint],
  ['roles', /^rol|^roles?$/i, ShieldCheck],
  ['folder', /klasör|folder/i, Folder],
  ['reverse', /reverse|keşif|discovery/i, ScanSearch],
  ['publication', /yayın|publication|^number$|^numara$/i, Rocket],
  ['definition', /^tanım$|^definition$|^tanım adı$/i, Layers3],
  ['duration', /^süre$|^duration$/i, Timer],
  ['updated', /^g[uü]ncellenen( satır)?$|^updated( rows?)?$/i, PencilLine],
  ['deleted', /^silinen( satır)?$|^deleted( rows?)?$/i, Trash2],
  ['trigger', /tetikleyici|trigger/i, Zap],
  ['technology', /teknoloji|technology/i, Cpu],
  ['hash', /özet|hash/i, Hash],
  ['catalog', /^katalog$|^catalog$/i, Table2],
  ['updatedAt', /g[uü]ncelle.*(zaman|tarih)|updat.*(at|time|date)|last.*update/i, Clock3],
  ['testTime', /son test|last test/i, Clock3],
  ['createdBy', /olu[sş]turan|ekleyen|created by|creator/i, UserPlus],
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
  ['action', /[İI]şlem|action/i, Settings2],
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
