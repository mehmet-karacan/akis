import { Database, Rows3, Timer } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { TransferProgress } from './types'

function readableBytes(bytes: number, locale: string) {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB']
  const index = bytes > 0 ? Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1) : 0
  return `${(bytes / 1024 ** index).toLocaleString(locale, { maximumFractionDigits: 2 })} ${units[index]}`
}

export function TransferProgressDetails({ progress }: { progress: TransferProgress }) {
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const number = (value: number) => value.toLocaleString(i18n.language, { maximumFractionDigits: 2 })
  const timings = [
    { label: tr ? 'Geçen süre' : 'Elapsed', seconds: progress.elapsedSeconds },
    { label: tr ? 'Okuma ve dönüştürme' : 'Read and conversion', seconds: progress.readSeconds },
    { label: tr ? 'Yazma' : 'Write', seconds: progress.writeSeconds },
    { label: 'Commit', seconds: progress.commitSeconds },
  ]
  const rate = progress.elapsedSeconds > 0 ? progress.rows / progress.elapsedSeconds : null
  return <section aria-label={tr ? 'Aktarım ilerlemesi' : 'Transfer progress'} aria-live="polite">
    <h4>{tr ? 'Aktarım ilerlemesi' : 'Transfer progress'}</h4>
    <div className="km-step-facts">
      <div><Rows3 /><span>{tr ? 'Aktarılan satır' : 'Transferred rows'}</span><strong>{number(progress.rows)}</strong></div>
      <div><Database /><span>{tr ? 'Aktarılan veri' : 'Transferred data'}</span><strong>{readableBytes(progress.bytes, i18n.language)}</strong></div>
      <div><Rows3 /><span>{tr ? 'Aktarım hızı' : 'Transfer rate'}</span><strong>{rate == null ? (tr ? 'Kullanılamıyor' : 'Not available') : `${number(rate)} ${tr ? 'satır' : 'rows'}/s`}</strong></div>
      {timings.filter(item => item.seconds != null).map(item => <div key={item.label}><Timer /><span>{item.label}</span><strong>{number(item.seconds!)} s</strong></div>)}
    </div>
  </section>
}
