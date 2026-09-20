import { useState } from 'react'
import { PlugZap } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { topologyApi } from '../topology/api'
import { notifyFeedback } from '../../core/api/networkFeedback'

export function ConnectionTestButton({ connectionUuid, onTested }: { connectionUuid: string; onTested?: () => void }) {
  const projectUuid = useCurrentProjectUuid()
  const { can } = useProjectAccess()
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const [busy, setBusy] = useState(false)
  if (!can('BAGLANTI_YONET')) return null
  async function test() {
    if (busy) return
    setBusy(true)
    try {
      const attempt = await topologyApi.testConnection(projectUuid, connectionUuid)
      const ok = attempt.outcome === 'PASSED'
      const message = ok ? `${tr ? 'Bağlantı başarılı' : 'Connection successful'}: ${attempt.durationMs} ms` : (attempt.errorCode || (tr ? 'Bağlantı testi başarısız.' : 'Connection test failed.'))
      notifyFeedback(message, ok ? 'success' : 'error')
      onTested?.()
    } catch (reason) {
      notifyFeedback(reason instanceof Error && reason.message ? reason.message : (tr ? 'Test tamamlanamadı.' : 'Test could not complete.'), 'error')
    }
    finally { setBusy(false) }
  }
  const label = tr ? 'Bağlantıyı Test Et' : 'Test Connection'
  return <div className="connection-inline-test" onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}><Button aria-label={label} title={label} icon={<PlugZap size={14} />} busy={busy} busyLabel={tr ? 'Test Ediliyor' : 'Testing'} onClick={() => void test()}><span className="connection-test-label">{label}</span></Button></div>
}
