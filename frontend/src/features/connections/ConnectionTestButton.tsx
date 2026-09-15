import { useState } from 'react'
import { ShieldCheck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { topologyApi } from '../topology/api'

export function ConnectionTestButton({ connectionUuid, versionUuid }: { connectionUuid: string; versionUuid?: string }) {
  const projectUuid = useCurrentProjectUuid()
  const { can } = useProjectAccess()
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null)
  if (!can('BAGLANTI_YONET')) return null
  async function test() {
    if (!versionUuid || busy) return
    setBusy(true); setResult(null)
    try {
      const attempt = await topologyApi.testConnectionVersion(projectUuid, connectionUuid, versionUuid)
      const ok = attempt.outcome === 'PASSED'
      setResult({ ok, text: ok ? `${tr ? 'Bağlantı Başarılı' : 'Connection Successful'} · ${attempt.durationMs} ms` : (tr ? 'Bağlantı Testi Başarısız' : 'Connection Test Failed') })
    } catch { setResult({ ok: false, text: tr ? 'Test tamamlanamadı. Bağlantı veya VPN erişimini kontrol edin.' : 'Test could not complete. Check connection or VPN access.' }) }
    finally { setBusy(false) }
  }
  return <div className="connection-inline-test" onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}><Button icon={<ShieldCheck size={14} />} disabled={!versionUuid} busy={busy} busyLabel={tr ? 'Test Ediliyor' : 'Testing'} onClick={() => void test()}>{tr ? 'Bağlantıyı Test Et' : 'Test Connection'}</Button>{result && <small role="status" className={result.ok ? 'connection-test-pass' : 'connection-test-pending'}>{result.text}</small>}</div>
}
