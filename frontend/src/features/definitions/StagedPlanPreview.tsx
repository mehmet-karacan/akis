import { useRef, useState } from 'react'
import { Alert, Space, Table } from 'antd'
import { ScanSearch } from 'lucide-react'
import { Button } from '../../core/ui/Button'
import { operationsApi } from '../operations/api'
import { notifyFeedback } from '../../core/api/networkFeedback'

export function StagedPlanPreview({ projectUuid, scenarioUuid, environmentUuid, tr, onReady }: { projectUuid: string; scenarioUuid: string; environmentUuid: string; tr: boolean; onReady: (hash: string) => void }) {
  const key = `${projectUuid}:${scenarioUuid}:${environmentUuid}`
  const latest = useRef(key); latest.current = key
  const [result, setResult] = useState<{ key: string; response: Awaited<ReturnType<typeof operationsApi.previewStagedPlan>> } | null>(null)
  const [busy, setBusy] = useState(false)
  async function preview() {
    setBusy(true); setResult(null)
    try {
      const response = await operationsApi.previewStagedPlan(projectUuid, scenarioUuid, environmentUuid)
      if (latest.current === key) { setResult({ key, response }); onReady(response.plan.physicalPlanHash) }
    } catch (reason) { if (latest.current === key) notifyFeedback(reason instanceof Error ? reason.message : 'Plan preview failed', 'error') }
    finally { setBusy(false) }
  }
  const visible = result?.key === key ? result.response : null
  return <Space orientation="vertical" style={{ width: '100%' }}>
    <Button type="button" icon={<ScanSearch size={16} />} busy={busy} disabled={!environmentUuid} onClick={() => void preview()}>{tr ? 'Çalışma Planını Önizle' : 'Preview Execution Plan'}</Button>
    {visible && <><Alert type="info" title={visible.message} /><Table size="small" pagination={false} rowKey={row => `${row.site}:${row.id}`} dataSource={visible.plan.steps}
      columns={[{ title: tr ? 'Adım' : 'Step', dataIndex: 'id' }, { title: tr ? 'Konum' : 'Site', dataIndex: 'site' }, { title: tr ? 'İşlem' : 'Operation', dataIndex: 'operation' }]} /></>}
  </Space>
}
