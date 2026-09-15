import { Children, isValidElement, useLayoutEffect, useRef, useState, type HTMLAttributes, type ReactElement, type ReactNode, type TdHTMLAttributes } from 'react'
import { Card, Table, Grid as AntGrid } from 'antd'
import { useTranslation } from 'react-i18next'
import { ViewToggle, type CollectionView } from './ViewToggle'
import { RecordFieldIcon, fieldText } from './RecordFieldIcon'
import { useRecordAudit, type AuditKind } from './useRecordAudit'

type Element = ReactElement<{ children?: ReactNode }>
function elements(children: ReactNode): Element[] {
  return Children.toArray(children).flatMap(child => {
    if (!isValidElement<{ children?: ReactNode }>(child)) return []
    return typeof child.type === 'string' ? [child] : elements(child.props.children)
  })
}
type GridRow = { key: string; cells: ReactElement<TdHTMLAttributes<HTMLTableCellElement>>[]; props: HTMLAttributes<HTMLTableRowElement> }

/** Ant Table renderer for declarative domain columns; preserves row actions and cell spans. */
type GridProps = HTMLAttributes<HTMLTableElement> & { viewControls?: boolean; auditKind?: AuditKind; collectionTitle?: string }
export function DataGrid(props: GridProps) {
  return props.auditKind ? <AuditedGrid {...props} auditKind={props.auditKind} /> : <Grid {...props} />
}
function AuditedGrid({ auditKind, children, ...props }: GridProps & { auditKind: AuditKind }) {
  const { i18n } = useTranslation()
  const audit = useRecordAudit(auditKind)
  const tr = i18n.language === 'tr'
  const titles: Record<AuditKind, string> = tr
    ? { connections: 'Bağlantı Listesi', 'logical-schemas': 'Mantıksal Şema Listesi', environments: 'Ortam Listesi', 'physical-schemas': 'Fiziksel Şema Listesi', models: 'Model Listesi', definitions: 'Nesne Listesi', folders: 'Klasör Listesi' }
    : { connections: 'Connection List', 'logical-schemas': 'Logical Schema List', environments: 'Environment List', 'physical-schemas': 'Physical Schema List', models: 'Model List', definitions: 'Object List', folders: 'Folder List' }
  const sections = elements(children)
  const head = sections.find(section => section.type === 'thead')
  const body = sections.find(section => section.type === 'tbody')
  const headers = elements(elements(head?.props.children)[0]?.props.children)
  const actionIndex = headers.findIndex(cell => /^(actions?|işlemler|işlem)$/i.test(fieldText(cell.props.children).trim()))
  const insertAt = actionIndex < 0 ? headers.length : actionIndex
  const labels = tr ? ['Oluşturan', 'Oluşturulma Zamanı', 'Güncelleyen', 'Güncellenme Zamanı'] : ['Created By', 'Created At', 'Updated By', 'Updated At']
  const missing = audit.state === 'error' ? (tr ? 'Bilgi alınamadı' : 'Unavailable') : audit.state === 'loading' ? (tr ? 'Yükleniyor…' : 'Loading…') : (tr ? 'Kaydedilmemiş' : 'Not Recorded')
  const date = (value: string | null | undefined) => value ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : missing
  return <Grid {...props} collectionTitle={props.viewControls === false ? undefined : titles[auditKind]}><thead><tr>{headers.slice(0, insertAt)}{labels.map(label => <th key={label}>{label}</th>)}{headers.slice(insertAt)}</tr></thead><tbody>{elements(body?.props.children).map(row => {
    const record = audit.records[String(row.key).replace(/^.*\$/, '')]
    const cells = elements(row.props.children)
    return <tr key={row.key} {...row.props}>{cells.slice(0, insertAt)}<td>{record?.createdBy || missing}</td><td>{date(record?.createdAt)}</td><td>{record?.updatedBy || missing}</td><td>{date(record?.updatedAt)}</td>{cells.slice(insertAt)}</tr>
  })}</tbody></Grid>
}
function Grid({ children, className, viewControls = true, auditKind: _, collectionTitle, ...props }: GridProps) {
  void _
  const [view, setView] = useState<CollectionView | null>(null)
  const screens = AntGrid.useBreakpoint()
  const { i18n } = useTranslation()
  const gridRef = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => { const table = gridRef.current?.querySelector('table'); if (table && props['aria-label']) table.setAttribute('aria-label', props['aria-label']) }, [props])
  const sections = elements(children)
  const header = sections.find(section => section.type === 'thead')
  const body = sections.find(section => section.type === 'tbody')
  const headerCells = elements(elements(header?.props.children)[0]?.props.children)
  const rows: GridRow[] = elements(body?.props.children).map((row, index) => {
    const { children: cells, ...rowProps } = row.props
    return { key: String(row.key ?? index), cells: elements(cells), props: rowProps }
  })
  const columns = headerCells.map((cell, index) => ({
    key: String(index), title: <span className="ui-field-heading"><RecordFieldIcon label={cell.props.children} />{cell.props.children}</span>,
    onHeaderCell: () => { const { children: _, ...attributes } = cell.props; void _; return attributes },
    render: (_: unknown, row: GridRow) => row.cells[index]?.props.children,
    onCell: (row: GridRow) => {
      const cell = row.cells[index]
      if (!cell) return { colSpan: 0 }
      const { children: _, ...attributes } = cell.props
      void _
      return attributes
    },
  }))
  const canChangeView = viewControls && !rows.some(row => row.cells.some(cell => (cell.props.colSpan ?? 1) !== 1 || (cell.props.rowSpan ?? 1) !== 1))
  const identityIndex = Math.max(0, headerCells.findIndex(cell => /^(name|ad|adı|model|bağlantı|connection|nesne|object|model adı|model name|şema adı|schema name|ortam adı|environment name|nesne adı|object name)$/i.test(fieldText(cell.props.children).trim())))
  const actionsIndex = headerCells.findIndex(cell => /^(actions?|işlemler|işlem)$/i.test(fieldText(cell.props.children).trim()))
  const activeView = canChangeView ? (view ?? (screens.md === false ? 'card' : 'table')) : 'table'
  return <div ref={gridRef} className={`ui-grid-container ${collectionTitle ? 'ui-grid-surface' : ''}`}>
    {(canChangeView || collectionTitle) && <div className="ui-grid-toolbar">{collectionTitle && <h2>{collectionTitle}</h2>}{canChangeView && <div className="ui-grid-view-control"><span>{i18n.language === 'tr' ? 'Görünüm' : 'View'}</span><ViewToggle value={activeView} onChange={setView} /></div>}</div>}
    {activeView === 'table' ? <Table<GridRow> className={`ui-data-grid ${className ?? ''}`} columns={columns} dataSource={rows}
    pagination={false} size="small" scroll={{ x: 'max-content' }} onRow={row => row.props}
    aria-label={props['aria-label']} /> : <div className={`ui-grid-records ui-grid-records--${activeView}`} role="list" aria-label={props['aria-label']}>
      {rows.map(row => <Card key={row.key} size="small" role="listitem" {...row.props as HTMLAttributes<HTMLDivElement>} className={`ui-grid-record ${row.props.className ?? ''}`}>
        <header><RecordFieldIcon label={headerCells[identityIndex]?.props.children} /><div>{row.cells[identityIndex]?.props.children}</div></header>
        <dl>{headerCells.map((headerCell, index) => {
          if (index === identityIndex || index === actionsIndex) return null
          const cell = row.cells[index]
          const { colSpan: _, rowSpan: __, children: content, ...attributes } = cell?.props ?? {}
          void _; void __
          return <div key={index} {...attributes as HTMLAttributes<HTMLDivElement>}><dt><RecordFieldIcon label={headerCell.props.children} />{headerCell.props.children}</dt><dd>{content}</dd></div>
        })}</dl>
        {actionsIndex >= 0 && <footer>{row.cells[actionsIndex]?.props.children}</footer>}
      </Card>)}
    </div>}
  </div>
}
