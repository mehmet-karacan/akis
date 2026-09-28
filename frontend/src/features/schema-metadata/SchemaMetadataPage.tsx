import { useCallback, useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Select as AntSelect, Table, Tabs, Tag } from 'antd'
import {
  BadgeCheck, BookOpenText, Braces, Check, Code2, Columns3, Database, FileText,
  Hash, KeyRound, ListOrdered, ListTree, RefreshCw, Ruler, Share2,
  ShieldCheck, Table2, Tags, Trash2, Type, X, type LucideIcon,
} from 'lucide-react'
import { AsyncState, PageHeader, RecordDetailDialog, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { RecordActionButton } from '../../core/ui/RecordActionButton'
import { QueryFilter } from '../../core/ui/QueryFilter'
import {
  schemaMetadataApi,
  type IliskiTanimi,
  type IndeksTanimi,
  type KisitTanimi,
  type KolonTanimi,
  type SemaTanimi,
  type SiraTanimi,
  type TabloTanimi,
} from './api'
import { findSequenceForColumn } from './sequenceAssociation'
import './schema-metadata.css'

interface TabloDetayVerisi {
  kolonlar: KolonTanimi[]
  kisitlar: KisitTanimi[]
  indeksler: IndeksTanimi[]
  iliskiler: IliskiTanimi[]
}

type MetadataIconTone = 'table' | 'columns' | 'constraints' | 'indexes' | 'relationships' | 'sequences' | 'success' | 'danger' | 'info' | 'description'

function MetadataColumnTitle({ icon: Icon, tone, children }: { icon: LucideIcon; tone: MetadataIconTone; children: ReactNode }) {
  return <span className={`schema-metadata-column-title schema-icon-${tone}`}><Icon size={14} aria-hidden="true" />{children}</span>
}

const metadataTagStyles: Record<'warning' | 'success' | 'subdued', CSSProperties> = {
  warning: {
    color: 'var(--schema-color-warning)',
    borderColor: 'color-mix(in srgb, var(--schema-color-warning) 38%, var(--line))',
    background: 'color-mix(in srgb, var(--schema-color-warning) 11%, var(--surface))',
  },
  success: {
    color: 'var(--schema-color-success)',
    borderColor: 'color-mix(in srgb, var(--schema-color-success) 38%, var(--line))',
    background: 'color-mix(in srgb, var(--schema-color-success) 11%, var(--surface))',
  },
  subdued: { color: 'var(--ink-soft)', borderColor: 'var(--line)', background: 'var(--surface-subtle)' },
}

function formatAuditDate(value: string | null | undefined, locale: string, fallback: string) {
  if (!value) return fallback
  return new Intl.DateTimeFormat(locale, {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value)).replace(',', '')
}

export function SchemaMetadataPage() {
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const missingAuditValue = tr ? 'Yok' : 'Not available'

  const [semalar, setSemalar] = useState<SemaTanimi[]>([])
  const [semaUuid, setSemaUuid] = useState<string>('')
  const [tablolar, setTablolar] = useState<TabloTanimi[]>([])
  const [sequences, setSequences] = useState<SiraTanimi[]>([])
  const [tableStats, setTableStats] = useState<Record<string, { columns: number; constraints: number; indexes: number; relationships: number }>>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')

  const [selectedTabloUuid, setSelectedTabloUuid] = useState<string>('')
  const [detay, setDetay] = useState<TabloDetayVerisi | null>(null)
  const [detayLoading, setDetayLoading] = useState(false)
  const [detayError, setDetayError] = useState('')
  const [panelOpen, setPanelOpen] = useState(false)

  const loadSemalar = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const next = await schemaMetadataApi.listSemalar()
      setSemalar(next)
      setSemaUuid((current) => current || next[0]?.uuid || '')
    } catch {
      setError(t('common.loadError'))
    } finally {
      setLoading(false)
    }
  }, [t])
  useEffect(() => { void loadSemalar() }, [loadSemalar])

  useEffect(() => {
    if (!semaUuid) { setTablolar([]); setSequences([]); setTableStats({}); return }
    setTableStats({})
    let cancelled = false
    Promise.all([schemaMetadataApi.listTablolar(semaUuid), schemaMetadataApi.listSequences(semaUuid)])
      .then(([nextTablolar, nextSequences]) => {
        if (cancelled) return
        setTablolar(nextTablolar)
        setSequences(nextSequences)
        Promise.all(nextTablolar.map(async (tablo) => {
          const [columns, constraints, indexes, relationships] = await Promise.all([
            schemaMetadataApi.listKolonlar(tablo.uuid),
            schemaMetadataApi.listKisitlar(tablo.uuid),
            schemaMetadataApi.listIndeksler(tablo.uuid),
            schemaMetadataApi.listIliskiler(tablo.uuid),
          ])
          return [tablo.uuid, { columns: columns.length, constraints: constraints.length, indexes: indexes.length, relationships: relationships.length }] as const
        })).then((entries) => {
          if (!cancelled) setTableStats(Object.fromEntries(entries))
        }).catch(() => { if (!cancelled) setTableStats({}) })
      })
      .catch(() => { if (!cancelled) setError(t('common.loadError')) })
    return () => { cancelled = true }
  }, [semaUuid, t])

  useEffect(() => {
    if (!selectedTabloUuid) { setDetay(null); return }
    let cancelled = false
    setDetayLoading(true); setDetayError('')
    Promise.all([
      schemaMetadataApi.listKolonlar(selectedTabloUuid),
      schemaMetadataApi.listKisitlar(selectedTabloUuid),
      schemaMetadataApi.listIndeksler(selectedTabloUuid),
      schemaMetadataApi.listIliskiler(selectedTabloUuid),
    ])
      .then(([kolonlar, kisitlar, indeksler, iliskiler]) => {
        if (cancelled) return
        setDetay({ kolonlar, kisitlar, indeksler, iliskiler })
      })
      .catch(() => { if (!cancelled) setDetayError(t('common.loadError')) })
      .finally(() => { if (!cancelled) setDetayLoading(false) })
    return () => { cancelled = true }
  }, [selectedTabloUuid, t])

  const semaOptions = useMemo(
    () => semalar.map((sema) => ({ value: sema.uuid, label: sema.ad })),
    [semalar],
  )

  const filteredTablolar = useMemo(() => {
    const text = query.trim().toLocaleLowerCase(i18n.language)
    return text ? tablolar.filter((tablo) => tablo.ad.toLocaleLowerCase(i18n.language).includes(text)) : tablolar
  }, [i18n.language, query, tablolar])

  const sequencesByTable = useMemo(() => Object.fromEntries(
    tablolar.map((tablo) => [tablo.uuid, sequences.filter((sequence) => sequence.ad.startsWith(`${tablo.ad}_`))]),
  ), [sequences, tablolar])

  const selectedTablo = tablolar.find((tablo) => tablo.uuid === selectedTabloUuid) ?? null
  const totals = Object.values(tableStats).reduce((sum, item) => ({
    columns: sum.columns + item.columns,
    constraints: sum.constraints + item.constraints,
    indexes: sum.indexes + item.indexes,
    relationships: sum.relationships + item.relationships,
  }), { columns: 0, constraints: 0, indexes: 0, relationships: 0 })
  const sequenceForColumn = (kolonAdi: string) => findSequenceForColumn(selectedTablo?.ad, kolonAdi, sequences)

  const openTablo = (uuid: string) => { setSelectedTabloUuid(uuid); setPanelOpen(true) }

  const detailTabs = detay ? [
    {
      key: 'kolonlar',
      label: <span className="schema-metadata-tab-label schema-tab-columns"><Columns3 size={14} /> {tr ? 'Kolonlar' : 'Columns'} ({detay.kolonlar.length})</span>,
      children: (
        <Table
          size="small"
          rowKey="uuid"
          pagination={false}
          dataSource={detay.kolonlar}
          columns={[
            { title: <MetadataColumnTitle icon={Hash} tone="info">#</MetadataColumnTitle>, dataIndex: 'siraNo', width: 54 },
            { title: <MetadataColumnTitle icon={Type} tone="columns">{tr ? 'Ad' : 'Name'}</MetadataColumnTitle>, dataIndex: 'ad', render: (value: string) => <code>{value}</code> },
            { title: <MetadataColumnTitle icon={Braces} tone="indexes">{tr ? 'Tip' : 'Type'}</MetadataColumnTitle>, dataIndex: 'veriTipi' },
            { title: <MetadataColumnTitle icon={Ruler} tone="info">{tr ? 'Uzunluk' : 'Length'}</MetadataColumnTitle>, dataIndex: 'uzunluk', render: (value: number | null) => value ?? '—' },
            { title: <MetadataColumnTitle icon={ShieldCheck} tone="constraints">{tr ? 'Zorunlu' : 'Required'}</MetadataColumnTitle>, dataIndex: 'zorunluMu', render: (value: boolean) => <Tag className={`schema-metadata-required-tag ${value ? 'is-yes' : 'is-no'}`} style={value ? metadataTagStyles.warning : metadataTagStyles.subdued} icon={value ? <Check size={12} /> : <X size={12} />}>{value ? (tr ? 'Evet' : 'Yes') : (tr ? 'Hayır' : 'No')}</Tag> },
            { title: <MetadataColumnTitle icon={RefreshCw} tone="info">{tr ? 'Varsayılan' : 'Default'}</MetadataColumnTitle>, dataIndex: 'varsayilanDeger', render: (value: string | null) => value ? <code>{value}</code> : '—' },
            { title: <MetadataColumnTitle icon={ListOrdered} tone="sequences">Sequence</MetadataColumnTitle>, dataIndex: 'ad', render: (value: string) => { const sequence = sequenceForColumn(value); return sequence ? <Tag className="schema-metadata-sequence-tag" style={metadataTagStyles.success} icon={<ListOrdered size={12} />}>{sequence.ad}</Tag> : '—' } },
            { title: <MetadataColumnTitle icon={FileText} tone="description">{tr ? 'Açıklama' : 'Description'}</MetadataColumnTitle>, dataIndex: 'aciklama', render: (value: string | null) => value ?? '—' },
          ]}
        />
      ),
    },
    {
      key: 'kisitlar',
      label: <span className="schema-metadata-tab-label schema-tab-constraints"><KeyRound size={14} /> {tr ? 'Kısıtlar' : 'Constraints'} ({detay.kisitlar.length})</span>,
      children: (
        <Table
          size="small"
          rowKey="uuid"
          pagination={false}
          dataSource={detay.kisitlar}
          columns={[
            { title: <MetadataColumnTitle icon={KeyRound} tone="constraints">{tr ? 'Ad' : 'Name'}</MetadataColumnTitle>, dataIndex: 'ad', render: (value: string) => <code>{value}</code> },
            { title: <MetadataColumnTitle icon={Tags} tone="constraints">{tr ? 'Tür' : 'Type'}</MetadataColumnTitle>, dataIndex: 'tur', render: (value: string) => <Tag className="schema-metadata-constraint-tag" style={metadataTagStyles.warning} icon={<KeyRound size={12} />}>{value}</Tag> },
            { title: <MetadataColumnTitle icon={Code2} tone="constraints">{tr ? 'CHECK İfadesi' : 'CHECK expression'}</MetadataColumnTitle>, dataIndex: 'checkIfadesi', render: (value: string | null) => value ? <code>{value}</code> : '—' },
          ]}
        />
      ),
    },
    {
      key: 'indeksler',
      label: <span className="schema-metadata-tab-label schema-tab-indexes"><ListTree size={14} /> {tr ? 'İndeksler' : 'Indexes'} ({detay.indeksler.length})</span>,
      children: (
        <Table
          size="small"
          rowKey="uuid"
          pagination={false}
          dataSource={detay.indeksler}
          columns={[
            { title: <MetadataColumnTitle icon={ListTree} tone="indexes">{tr ? 'Ad' : 'Name'}</MetadataColumnTitle>, dataIndex: 'ad', render: (value: string) => <code>{value}</code> },
            { title: <MetadataColumnTitle icon={Tags} tone="indexes">{tr ? 'Tür' : 'Type'}</MetadataColumnTitle>, dataIndex: 'tur' },
            { title: <MetadataColumnTitle icon={BadgeCheck} tone="success">{tr ? 'Benzersiz' : 'Unique'}</MetadataColumnTitle>, dataIndex: 'benzersizMi', render: (value: boolean) => <Tag className={`schema-metadata-unique-tag ${value ? 'is-yes' : 'is-no'}`} style={value ? metadataTagStyles.success : metadataTagStyles.subdued} icon={value ? <Check size={12} /> : <X size={12} />}>{value ? (tr ? 'Evet' : 'Yes') : (tr ? 'Hayır' : 'No')}</Tag> },
          ]}
        />
      ),
    },
    {
      key: 'iliskiler',
      label: <span className="schema-metadata-tab-label schema-tab-relationships"><Share2 size={14} /> {tr ? 'İlişkiler' : 'Relationships'} ({detay.iliskiler.length})</span>,
      children: (
        <Table
          size="small"
          rowKey="uuid"
          pagination={false}
          dataSource={detay.iliskiler}
          columns={[
            { title: <MetadataColumnTitle icon={KeyRound} tone="constraints">{tr ? 'Kaynak Kısıt' : 'Source Constraint'}</MetadataColumnTitle>, dataIndex: 'kaynakKisitAdi', render: (value: string) => <code>{value}</code> },
            { title: <MetadataColumnTitle icon={Table2} tone="table">{tr ? 'Hedef Tablo' : 'Target Table'}</MetadataColumnTitle>, dataIndex: 'hedefTabloAdi', render: (value: string) => <code>{value}</code> },
            { title: <MetadataColumnTitle icon={KeyRound} tone="constraints">{tr ? 'Hedef Kısıt' : 'Target Constraint'}</MetadataColumnTitle>, dataIndex: 'hedefKisitAdi', render: (value: string) => <code>{value}</code> },
            { title: <MetadataColumnTitle icon={Trash2} tone="danger">ON DELETE</MetadataColumnTitle>, dataIndex: 'silmeKurali' },
            { title: <MetadataColumnTitle icon={RefreshCw} tone="info">ON UPDATE</MetadataColumnTitle>, dataIndex: 'guncellemeKurali' },
          ]}
        />
      ),
    },
  ] : []

  return (
    <section className="page-stack schema-metadata-page">
      <PageHeader
        icon={<BookOpenText />}
        eyebrow={tr ? 'Veri Sözlüğü' : 'Data Dictionary'}
        title={tr ? 'Şema Metadata Sözlüğü' : 'Schema Metadata Dictionary'}
        description={tr
          ? 'Sistemin kendi veritabanı yapısını (şema, tablo, kolon, kısıt, ilişki, indeks, sequence) tanımlayan self-hosting sözlük.'
          : 'The self-hosting dictionary describing the system\'s own database structure (schema, table, column, constraint, relationship, index, sequence).'}
        actions={semaOptions.length > 1 ? (
          <label className="schema-metadata-schema-select">
            <span><Database size={15} aria-hidden="true" />{tr ? 'Şema' : 'Schema'}</span>
            <AntSelect value={semaUuid} onChange={setSemaUuid} options={semaOptions} style={{ minWidth: 180 }} />
          </label>
        ) : undefined}
      />

      <QueryFilter onApply={setQuery} placeholder={tr ? 'Tablo ara…' : 'Search tables…'} />

      {error ? <div className="error-banner" role="alert">{error}</div> : null}
      {loading ? <AsyncState state="loading" title={t('common.loading')} /> : semalar.length === 0 ? (
        <AsyncState state="empty" title={tr ? 'Kayıtlı şema tanımı yok.' : 'No schema definitions yet.'} />
      ) : (
        <>
          <SummaryStrip
            ariaLabel={tr ? 'Şema Metadata Özeti' : 'Schema Metadata Summary'}
            items={[
              { label: tr ? 'Tablolar' : 'Tables', value: tablolar.length, icon: <Table2 />, tone: 'info' },
              { label: tr ? 'Toplam Kolon' : 'Total Columns', value: totals.columns, icon: <Columns3 />, tone: 'teal' },
              { label: tr ? 'Kısıtlar' : 'Constraints', value: totals.constraints, icon: <KeyRound />, tone: 'warning' },
              { label: tr ? 'İndeksler' : 'Indexes', value: totals.indexes, icon: <ListTree />, tone: 'neutral' },
              { label: tr ? 'İlişkiler' : 'Relationships', value: totals.relationships, icon: <Share2 />, tone: 'pink' },
              { label: tr ? 'Sequence' : 'Sequences', value: sequences.length, icon: <ListOrdered />, tone: 'success' },
            ]}
          />

          <div className="schema-metadata-table-wrap">
            <DataGrid collectionTitle={tr ? 'Tablo Kataloğu' : 'Table Catalog'} collectionIcon={<Table2 />} auditInFooter cardHeaderField="sequence">
              <thead>
                <tr>
                  <th>{tr ? 'Tablo' : 'Table'}</th>
                  <th>{tr ? 'Açıklama' : 'Description'}</th>
                  <th>{tr ? 'Kolon Sayısı' : 'Column Count'}</th>
                  <th>{tr ? 'Kısıt Sayısı' : 'Constraint Count'}</th>
                  <th>{tr ? 'İndeks Sayısı' : 'Index Count'}</th>
                  <th>{tr ? 'İlişki Sayısı' : 'Relationship Count'}</th>
                  <th>{tr ? 'Kullandığı Sequence' : 'Sequence Used'}</th>
                  <th className="ui-record-audit-column">{tr ? 'Oluşturan' : 'Created By'}</th>
                  <th className="ui-record-audit-column">{tr ? 'Oluşturulma Zamanı' : 'Created At'}</th>
                  <th className="ui-record-audit-column">{tr ? 'Güncelleyen' : 'Updated By'}</th>
                  <th className="ui-record-audit-column">{tr ? 'Güncellenme Zamanı' : 'Updated At'}</th>
                  <th className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th>
                </tr>
              </thead>
              <tbody>
                {filteredTablolar.map((tablo) => (
                  <tr
                    key={tablo.uuid}
                  >
                    <td>
                      <span className="schema-metadata-tablo-cell"><code>{tablo.ad}</code></span>
                    </td>
                    <td>{tablo.aciklama ?? '—'}</td>
                    <td className="schema-metadata-count-cell"><strong>{tableStats[tablo.uuid]?.columns ?? '—'}</strong></td>
                    <td className="schema-metadata-count-cell"><strong>{tableStats[tablo.uuid]?.constraints ?? '—'}</strong></td>
                    <td className="schema-metadata-count-cell"><strong>{tableStats[tablo.uuid]?.indexes ?? '—'}</strong></td>
                    <td className="schema-metadata-count-cell"><strong>{tableStats[tablo.uuid]?.relationships ?? '—'}</strong></td>
                    <td className="schema-metadata-sequence-cell">
                      {sequencesByTable[tablo.uuid]?.length ? (
                        <div className="schema-metadata-sequence-usage">
                          {sequencesByTable[tablo.uuid]?.map((sequence) => (
                            <span key={sequence.uuid} title={sequence.ad}><ListOrdered size={13} aria-hidden="true" /><code>{sequence.ad}</code></span>
                          ))}
                        </div>
                      ) : <span className="schema-metadata-sequence-none">{tr ? 'Yok' : 'None'}</span>}
                    </td>
                    <td className="ui-record-audit-column">{tablo.createdBy ?? missingAuditValue}</td>
                    <td className="ui-record-audit-column">{formatAuditDate(tablo.createdAt, i18n.language, missingAuditValue)}</td>
                    <td className="ui-record-audit-column">{tablo.updatedBy ?? missingAuditValue}</td>
                    <td className="ui-record-audit-column">{formatAuditDate(tablo.updatedAt, i18n.language, missingAuditValue)}</td>
                    <td className="row-actions ui-grid-actions-column"><RecordActionButton name={tablo.ad} editable={false} onClick={() => openTablo(tablo.uuid)} /></td>
                  </tr>
                ))}
              </tbody>
            </DataGrid>
            {filteredTablolar.length === 0 ? <AsyncState state="empty" compact title={tr ? 'Eşleşen tablo yok.' : 'No matching tables.'} /> : null}
          </div>
        </>
      )}

      <RecordDetailDialog
        open={panelOpen && Boolean(selectedTablo)}
        title={selectedTablo ? <span className="schema-metadata-dialog-title"><Table2 size={17} aria-hidden="true" /><code>{selectedTablo.ad}</code></span> : ''}
        onClose={() => setPanelOpen(false)}
        className="schema-metadata-panel-modal"
      >
        {selectedTablo ? (
          <div className="schema-metadata-panel">
            {selectedTablo.aciklama ? <p className="schema-metadata-panel-description"><FileText size={16} aria-hidden="true" /><span>{selectedTablo.aciklama}</span></p> : null}
            {detayLoading ? <AsyncState state="loading" title={t('common.loading')} /> : detayError ? (
              <AsyncState state="error" title={t('common.loadError')} />
            ) : <Tabs items={detailTabs} />}
          </div>
        ) : null}
      </RecordDetailDialog>
    </section>
  )
}
