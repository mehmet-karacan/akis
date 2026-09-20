import { Alert, Descriptions, Space, Table, Tag } from 'antd'
import { useTranslation } from 'react-i18next'

export interface KmRunData {
  reconciliation?: { outcome: string; rows: number | null } | null
  steps: { generation: number; ordinal: number; stepCode: string; operation: string; site: string; slot: string; state: string; affectedRows: number | null; errorCode: string | null; startedAt: string | null; completedAt: string | null }[]
  workObjects: { uuid: string; owner: string; name: string; state: string; rows: number | null; bytes: number | null }[]
}
const operations: Record<string, [string, string]> = {
  CREATE_WORK: ['Çalışma Tablosunu Hazırla', 'Prepare Work Table'], TRANSFER_JDBC: ['Kaynağı Çalışma Alanına Aktar', 'Load Source into Work Area'],
  SEAL_WORK: ['Aktarımı Doğrula', 'Verify Transfer'], CHECK_NOT_NULL: ['Zorunlu Alanları Kontrol Et', 'Check Required Columns'],
  CHECK_UNIQUE: ['Benzersiz Anahtarları Kontrol Et', 'Check Unique Keys'], ATOMIC_REPLACE: ['Hedefi Atomik Yenile', 'Refresh Target Atomically'],
}
const states: Record<string, [string, string, string]> = {
  PENDING: ['Bekliyor', 'Pending', 'default'], RUNNING: ['Çalışıyor', 'Running', 'processing'], SUCCEEDED: ['Başarılı', 'Succeeded', 'success'],
  FAILED: ['Başarısız', 'Failed', 'error'], UNKNOWN: ['Sonuç Belirsiz', 'Outcome Unknown', 'warning'],
  ALLOCATED: ['Tahsis Edildi', 'Allocated', 'default'], CREATING: ['Oluşturuluyor', 'Creating', 'processing'], READY: ['Hazır', 'Ready', 'default'],
  LOADING: ['Yükleniyor', 'Loading', 'processing'], SEALED: ['Doğrulandı', 'Sealed', 'success'], CONSUMED: ['Hedefe Uygulandı', 'Published', 'success'],
  CLEANUP_PENDING: ['Temizleme Bekliyor', 'Cleanup Pending', 'warning'], DROPPED: ['Temizlendi', 'Cleaned', 'default'], REVIEW_REQUIRED: ['İnceleme Gerekli', 'Review Required', 'warning'],
}
export function KmRunDetails({ data }: { data: KmRunData }) {
  const { i18n } = useTranslation()
  const language = i18n.language.startsWith('tr') ? 0 : 1
  const tr = language === 0
  const state = (value: string) => <Tag color={states[value]?.[2] ?? 'default'}>{states[value]?.[language] ?? value}</Tag>
  const latest = Math.max(0, ...data.steps.map(step => step.generation))
  const current = data.steps.filter(step => step.generation === latest)
  const reads = current.find(step => step.operation === 'TRANSFER_JDBC' && step.state === 'SUCCEEDED')?.affectedRows
  const writes = data.reconciliation?.outcome === 'PUBLISHED' ? data.reconciliation.rows : current.find(step => step.operation === 'ATOMIC_REPLACE' && step.state === 'SUCCEEDED')?.affectedRows
  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <Descriptions size="small" bordered column={{ xs: 1, sm: 2 }} items={[
      { key: 'read', label: tr ? 'Kaynak Satır' : 'Source Rows', children: reads ?? (tr ? 'Kaydedilmedi' : 'Not recorded') },
      { key: 'write', label: tr ? 'Hedefe Eklenen Satır' : 'Inserted Rows', children: writes ?? (tr ? 'Kaydedilmedi' : 'Not recorded') },
    ]} />
    {data.reconciliation && <Alert type={data.reconciliation.outcome === 'PUBLISHED' ? 'success' : 'warning'} showIcon title={data.reconciliation.outcome === 'PUBLISHED' ? (tr ? 'Hedef yayını mutabakat ile doğrulandı.' : 'Target publication confirmed by reconciliation.') : data.reconciliation.outcome === 'NOT_PUBLISHED' ? (tr ? 'Hedefe yazılmadığı doğrulandı.' : 'Confirmed that no target publication occurred.') : (tr ? 'Yayın kanıtında çakışma var.' : 'Publication evidence conflict.')} description={tr ? 'İlk çalıştırmanın adım kayıtları korunur. Çalışma tabloları inceleme için saklanır; işlem tekrar çalıştırılmaz.' : 'Original step records are preserved. Work tables remain available for review; the operation is not replayed.'} />}
    {!data.reconciliation && current.some(step => step.state === 'UNKNOWN') && <Alert type="warning" showIcon title={tr ? 'Hedef sonucu doğrulanmalı. Aynı işlem otomatik tekrar edilmez.' : 'The target outcome must be reconciled. The operation is not automatically retried.'} />}
    <Table size="small" pagination={false} dataSource={data.steps} rowKey={row => `${row.generation}-${row.ordinal}`} scroll={{ x: 'max-content' }} columns={[
      { title: '#', dataIndex: 'ordinal', width: 48 },
      { title: tr ? 'Adım' : 'Step', dataIndex: 'operation', render: value => operations[value]?.[language] ?? value },
      { title: tr ? 'Konum' : 'Location', dataIndex: 'site', render: value => value === 'STAGING' ? (tr ? 'Çalışma Alanı' : 'Work Area') : (tr ? 'Hedef' : 'Target') },
      { title: tr ? 'Durum' : 'Status', dataIndex: 'state', render: state },
      { title: tr ? 'Satır' : 'Rows', dataIndex: 'affectedRows', render: (value, row) => ['TRANSFER_JDBC', 'ATOMIC_REPLACE'].includes(row.operation) ? value ?? (tr ? 'Kaydedilmedi' : 'Not recorded') : '' },
      { title: tr ? 'Hata' : 'Error', dataIndex: 'errorCode', render: value => value ?? (tr ? 'Bulunmuyor' : 'None') },
    ]} />
    {data.workObjects.length > 0 && <Table size="small" pagination={false} dataSource={data.workObjects} rowKey="uuid" scroll={{ x: 'max-content' }} columns={[
      { title: tr ? 'Çalışma Şeması' : 'Work Schema', dataIndex: 'owner' },
      { title: tr ? 'Çalışma Tablosu' : 'Work Table', dataIndex: 'name' },
      { title: tr ? 'Durum' : 'Status', dataIndex: 'state', render: state },
      { title: tr ? 'Satır' : 'Rows', dataIndex: 'rows', render: value => value ?? (tr ? 'Kaydedilmedi' : 'Not recorded') },
    ]} />}
  </Space>
}
