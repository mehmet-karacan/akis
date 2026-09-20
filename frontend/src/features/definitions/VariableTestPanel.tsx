import { Alert, Button, Descriptions, Select, Table, Tag } from 'antd'
import { FlaskConical, RefreshCw } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { apiRequest, jsonBody } from '../../core/api/client'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { FeedbackToast } from '../../core/ui/FeedbackToast'
import { topologyApi, type Environment } from '../topology/api'
import { useDefinitionsI18n } from './i18n'
import { variableTestFeedback } from './variableTestFeedback'

export interface VariableTestResult {
  id: number; kind: 'TEST'; success: boolean; value: string | null; dataType: string
  durationMs: number; errorCode: string | null; environment: string; logicalSchema: string; createdAt: string
}
interface Props { projectUuid: string; definitionUuid: string; content: Record<string, unknown> }
export function VariableTestPanel(props: Props) {
  // Definition navigation must discard local results, selection and pending UI
  // state. The old instance still invalidates its in-flight responses on unmount.
  return <VariableTestSession key={JSON.stringify([props.projectUuid, props.definitionUuid])} {...props} />
}

function VariableTestSession({ projectUuid, definitionUuid, content }: Props) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const { can } = useProjectAccess()
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [environment, setEnvironment] = useState<string>()
  const [history, setHistory] = useState<VariableTestResult[]>([])
  const [result, setResult] = useState<VariableTestResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [revision, setRevision] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [loadingContext, setLoadingContext] = useState(true)
  const [testedInput, setTestedInput] = useState('')
  const [notice, setNotice] = useState<{ text: string; tone: 'success' | 'error' } | null>(null)
  const requestId = useRef(0)
  const endpoint = `/api/v1/projects/${encodeURIComponent(projectUuid)}/definitions/${encodeURIComponent(definitionUuid)}/value-tests`
  const query = typeof content.query === 'string' ? content.query : ''
  const dataType = content.dataType ?? 'DATE'
  const currentInput = JSON.stringify([query, dataType, content.logicalSchemaUuid, environment])
  const validQuery = query.trim().length > 0 && query.length <= 20000
  const activeEnvironment = environments.some(item => item.uuid === environment)
  const canTest = !busy && !loadingMore && !loadingContext && !loadError && activeEnvironment && validQuery
    && !!content.logicalSchemaUuid && can('TANIM_DUZENLE') && can('CALISTIRMA_BASLAT')
  useEffect(() => {
    let active = true
    setLoadingContext(true)
    Promise.all([topologyApi.listEnvironments(projectUuid), apiRequest<VariableTestResult[]>(endpoint)])
      .then(([items, rows]) => { if (active) { setEnvironments(items.filter(item => ['AKTIF', 'ACTIVE'].includes(item.status))); setHistory(rows); setHasMore(rows.length === 50); setLoadError('') } })
      .catch(error => { if (active) setLoadError(error instanceof Error ? error.message : 'Failed to load test context') })
      .finally(() => { if (active) setLoadingContext(false) })
    return () => { active = false }
  }, [projectUuid, endpoint, revision])
  useEffect(() => () => { requestId.current++ }, [endpoint])
  const loadMore = async () => {
    const before = history.at(-1)?.id
    if (loadingMore || before == null) return
    const id = requestId.current
    setLoadingMore(true)
    try {
      const rows = await apiRequest<VariableTestResult[]>(`${endpoint}?before=${before}`)
      if (id !== requestId.current) return
      setHistory(existing => [...existing, ...rows.filter(row => !existing.some(item => item.id === row.id))])
      setHasMore(rows.length === 50)
    } catch (error) {
      if (id === requestId.current) setNotice({ tone: 'error', text: error instanceof Error ? error.message : (tr ? 'Geçmiş yüklenemedi.' : 'Failed to load history.') })
    } finally { if (id === requestId.current) setLoadingMore(false) }
  }
  const test = async () => {
    if (!canTest) return
    const id = ++requestId.current
    setBusy(true); setResult(null)
    try {
      const response = await apiRequest<VariableTestResult>(endpoint, { method: 'POST', ...jsonBody({
        logicalSchemaUuid: content.logicalSchemaUuid, environmentUuid: environment,
        dataType, query,
      }) })
      if (id !== requestId.current) return
      setResult(response); setTestedInput(currentInput); setHistory(rows => [response, ...rows.filter(row => row.id !== response.id)])
      setNotice({ tone: response.success ? 'success' : 'error', text: response.success
        ? (tr ? 'Değişken testi başarılı. Tanım ve son çalıştırma değeri değiştirilmedi.' : 'Variable test passed. Definition and last execution value were not changed.')
        : variableTestFeedback(response.errorCode, tr) })
    } catch (error) {
      if (id === requestId.current) setNotice({ tone: 'error', text: error instanceof Error ? error.message : (tr ? 'Test başarısız.' : 'Test failed.') })
    } finally { if (id === requestId.current) setBusy(false) }
  }
  return <section className="structured-draft-wide variable-test-panel" aria-label={tr ? 'Değişken Testi' : 'Variable Test'}>
    <FeedbackToast message={notice?.text ?? ''} tone={notice?.tone} onClose={() => setNotice(null)} />
    <header><h3><FlaskConical size={17} /> {tr ? 'Değişken Testi' : 'Variable Test'}</h3>
      <Button disabled={busy || loadingMore || loadingContext} icon={<RefreshCw size={15} />} onClick={() => setRevision(value => value + 1)}>{tr ? 'Geçmişi Yenile' : 'Refresh History'}</Button></header>
    <p>{tr ? 'Ekrandaki sorgu seçtiğiniz ortamda test edilir. Kaydetme yapılmaz; testler gerçek çalıştırma geçmişinden ayrı tutulur.' : 'Test the current query in the selected environment without saving. Tests are kept separately from execution values.'}</p>
    {loadError && <Alert type="error" title={loadError} />}
    <div className="variable-test-toolbar"><label><span>{tr ? 'Test Ortamı' : 'Test Environment'}</span>
      <Select virtual={false} aria-label={tr ? 'Test Ortamı' : 'Test Environment'} value={environment} onChange={setEnvironment} placeholder={tr ? 'Ortam Seçin' : 'Select Environment'} options={environments.map(item => ({ value: item.uuid, label: item.name }))} /></label>
      <Button type="primary" icon={<FlaskConical size={16} />} loading={busy} disabled={!canTest} onClick={() => void test()}>{tr ? 'Çalıştır ve Test Et' : 'Run and Test'}</Button>
    </div>
    {!validQuery && <Alert type="info" title={tr ? 'Test için sorgu alanına en fazla 20.000 karakterlik bir SQL sorgusu yazın. Örnek sorgu otomatik çalıştırılmaz.' : 'Enter a SQL query of up to 20,000 characters before testing. No sample query is executed automatically.'} />}
    {!loadingContext && !loadError && environment && !activeEnvironment && <Alert type="warning" title={tr ? 'Seçilen ortam artık aktif değil. Aktif bir test ortamı seçin.' : 'The selected environment is no longer active. Choose an active test environment.'} />}
    {result && testedInput !== currentInput && <Alert type="info" title={tr ? 'Tanım veya ortam değişti. Aşağıdaki sonuç önceki test girdilerine aittir; yeniden test edin.' : 'The definition or environment has changed. The result below belongs to the previous test inputs; test again.'} />}
    {result && <Descriptions size="small" bordered column={{ xs: 1, sm: 2, lg: 3 }} items={[
      { key: 'result', label: tr ? 'Sonuç' : 'Result', span: 'filled', children: result.success ? result.value : variableTestFeedback(result.errorCode, tr) },
      { key: 'type', label: tr ? 'Veri Tipi' : 'Data Type', children: result.dataType },
      { key: 'time', label: tr ? 'Süre' : 'Duration', children: `${result.durationMs} ms` },
      { key: 'context', label: tr ? 'Bağlam' : 'Context', children: `${result.logicalSchema} / ${result.environment}` },
    ]} />}
    <h4>{tr ? 'Test Geçmişi' : 'Test History'}</h4>
    <Table size="small" rowKey="id" dataSource={history} scroll={{ x: 700 }} pagination={{ pageSize: 5, hideOnSinglePage: true }} columns={[
      { title: tr ? 'Tür' : 'Kind', key: 'kind', render: () => <Tag>Test</Tag>, width: 70 },
      { title: tr ? 'Durum' : 'Status', key: 'status', render: (_, row) => <Tag color={row.success ? 'success' : 'error'}>{row.success ? (tr ? 'Başarılı' : 'Passed') : (tr ? 'Başarısız' : 'Failed')}</Tag> },
      { title: tr ? 'Değer / Hata' : 'Value / Error', key: 'value', render: (_, row) => row.success ? row.value : variableTestFeedback(row.errorCode, tr) },
      { title: tr ? 'Veri Tipi' : 'Data Type', dataIndex: 'dataType' },
      { title: tr ? 'Ortam' : 'Environment', dataIndex: 'environment' },
      { title: tr ? 'Mantıksal Şema' : 'Logical Schema', dataIndex: 'logicalSchema' },
      { title: tr ? 'Süre' : 'Duration', dataIndex: 'durationMs', render: value => `${value} ms` },
      { title: tr ? 'Zaman' : 'Time', dataIndex: 'createdAt', render: value => new Date(value).toLocaleString(language) },
    ]} />
    {hasMore && <Button disabled={busy || loadingContext} loading={loadingMore} onClick={() => void loadMore()}>{tr ? 'Önceki Testleri Yükle' : 'Load Earlier Tests'}</Button>}
  </section>
}
