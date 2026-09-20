import { Children, isValidElement, useLayoutEffect, useRef, useState, type HTMLAttributes, type ReactElement, type ReactNode, type TdHTMLAttributes } from 'react'
import { Table, Grid as AntGrid } from 'antd'
import { Eye } from 'lucide-react'
import { RecordCard } from './RecordCard'
import { useTranslation } from 'react-i18next'
import { ViewToggle, type CollectionView } from './ViewToggle'
import { RecordFieldIcon, fieldText } from './RecordFieldIcon'
import { useRecordAudit, type AuditKind } from './useRecordAudit'
import { recordAuditPresentation } from './recordAuditPresentation'

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- children carry arbitrary intrinsic props
type Element = ReactElement<any>
function elements(children: ReactNode): Element[] {
  return Children.toArray(children).flatMap(child => {
    if (!isValidElement<{ children?: ReactNode }>(child)) return []
    return typeof child.type === 'string' ? [child] : elements(child.props.children)
  })
}
type GridRow = { key: string; cells: ReactElement<TdHTMLAttributes<HTMLTableCellElement>>[]; props: HTMLAttributes<HTMLTableRowElement> & { 'data-connection-uuid'?: string } }

/** Ant Table renderer for declarative domain columns; preserves row actions and cell spans. */
type GridProps = HTMLAttributes<HTMLTableElement> & { viewControls?: boolean; auditKind?: AuditKind; collectionTitle?: string; collectionIcon?: ReactNode; toolbarActions?: ReactNode; auditInFooter?: boolean; cardHeaderField?: string; cardHeaderLeadingField?: string; cardHiddenFields?: string[]; headerFieldsInList?: boolean; view?: CollectionView; onViewChange?: (view: CollectionView) => void }
export function DataGrid(props: GridProps) {
  return props.auditKind ? <AuditedGrid {...props} auditKind={props.auditKind} /> : <Grid {...props} />
}
function AuditedGrid({ auditKind, children, ...props }: GridProps & { auditKind: AuditKind }) {
  const { i18n } = useTranslation()
  const audit = useRecordAudit(auditKind)
  const tr = i18n.language === 'tr'
  const titles: Record<AuditKind, string> = tr
    ? { connections: 'Bağlantı Listesi', 'logical-schemas': 'Mantıksal Şema Listesi', environments: 'Ortam Listesi', 'physical-schemas': 'Fiziksel Şema Listesi', models: 'Model Listesi', 'data-objects': 'Data Store Listesi', definitions: 'Nesne Listesi', folders: 'Klasör Listesi' }
    : { connections: 'Connection List', 'logical-schemas': 'Logical Schema List', environments: 'Environment List', 'physical-schemas': 'Physical Schema List', models: 'Model List', 'data-objects': 'Data Store List', definitions: 'Object List', folders: 'Folder List' }
  const sections = elements(children)
  const head = sections.find(section => section.type === 'thead')
  const body = sections.find(section => section.type === 'tbody')
  const headers = elements(elements(head?.props.children)[0]?.props.children)
  const actionIndex = headers.findIndex(cell => /^(actions?|işlemler|işlem)$/.test(fieldText(cell.props.children).trim().toLocaleLowerCase('tr')))
  const insertAt = actionIndex < 0 ? headers.length : actionIndex
  const labels = tr ? ['Oluşturan', 'Oluşturulma Zamanı', 'Güncelleyen', 'Güncellenme Zamanı'] : ['Created By', 'Created At', 'Updated By', 'Updated At']
  return <Grid {...props} collectionTitle={props.collectionTitle ?? (props.viewControls === false ? undefined : titles[auditKind])}><thead><tr>{headers.slice(0, insertAt)}{labels.map(label => <th className="ui-record-audit-column" key={label}>{label}</th>)}{headers.slice(insertAt)}</tr></thead><tbody>{elements(body?.props.children).map(row => {
    const record = recordAuditPresentation(audit.records[String(row.key).replace(/^.*\$/, '')], audit.state, i18n.language)
    const cells = elements(row.props.children)
    return <tr key={row.key} {...row.props}>{cells.slice(0, insertAt)}<td className="ui-record-audit-column">{record.createdBy}</td><td className="ui-record-audit-column">{record.createdAt}</td><td className="ui-record-audit-column">{record.updatedBy}</td><td className="ui-record-audit-column">{record.updatedAt}</td>{cells.slice(insertAt)}</tr>
  })}</tbody></Grid>
}
function Grid({ children, className, viewControls = true, auditKind: _, collectionTitle, collectionIcon, toolbarActions, auditInFooter = false, cardHeaderField, cardHeaderLeadingField, cardHiddenFields = [], headerFieldsInList = false, view: controlledView, onViewChange, ...props }: GridProps) {
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
  const fieldKey = (cell?: Element) => String(cell?.props['data-field-key'] ?? '').trim().toLowerCase()
  const identityIndex = Math.max(0, headerCells.findIndex(cell => fieldKey(cell) === 'name' || /^(name|ad|adı|model|bağlantı|connection|nesne|object|data store|datastore|column name|kolon adı|model adı|model name|şema adı|schema name|ortam adı|environment name|nesne adı|object name)$/i.test(fieldText(cell.props.children).trim())))
  const actionsIndex = headerCells.findIndex(cell => fieldKey(cell) === 'actions' || /^(actions?|işlemler|işlem|record information|kayıt bilgileri)$/.test(fieldText(cell.props.children).trim().toLocaleLowerCase('tr')))
  const fieldIndex = (fieldName?: string) => fieldName ? headerCells.findIndex(cell => fieldKey(cell) === fieldName.toLowerCase() || new RegExp(fieldName, 'i').test(fieldText(cell.props.children).trim())) : -1
  const cardHeaderFieldIndex = fieldIndex(cardHeaderField)
  const cardHeaderLeadingFieldIndex = fieldIndex(cardHeaderLeadingField)
  const cardHiddenIndexes = new Set(cardHiddenFields.map(fieldName => fieldIndex(fieldName)).filter(index => index >= 0))
  const isAudit = (index: number) => ['createdby', 'createdat', 'updatedby', 'updatedat'].includes(fieldKey(headerCells[index])) || /^(Created By|Created At|Updated By|Updated At|Oluşturan|Oluşturulma Zamanı|Güncelleyen|Güncellenme Zamanı|Ekleyen|Eklenme Zamanı|Güncelleyen|Güncellenme Zamanı)$/i.test(fieldText(headerCells[index]?.props.children).trim())
  const field = (row: GridRow, index: number) => <div key={index} data-field-key={fieldText(headerCells[index]?.props.children)}><dt><RecordFieldIcon label={headerCells[index]?.props.children} />{headerCells[index]?.props.children}</dt><dd>{row.cells[index]?.props.children}</dd></div>
  const activeView = canChangeView ? (controlledView ?? view ?? (screens.md === false ? 'card' : 'table')) : 'table'
  return <div ref={gridRef} className={`ui-grid-container ${collectionTitle ? 'ui-grid-surface' : ''}`}>
    {(canChangeView || collectionTitle || toolbarActions) && <div className="ui-grid-toolbar">{collectionTitle && <h2>{collectionIcon ? <span className="ui-grid-collection-icon" aria-hidden="true">{collectionIcon}</span> : null}{collectionTitle}</h2>}<div className="ui-grid-toolbar-end">{toolbarActions}{canChangeView && <div className="ui-grid-view-control"><span className="ui-grid-view-label"><Eye size={15} aria-hidden="true" />{i18n.language === 'tr' ? 'Görünüm' : 'View'}</span><ViewToggle value={activeView} onChange={(next) => { setView(next); onViewChange?.(next) }} /></div>}</div></div>}
    {activeView === 'table' ? <Table<GridRow> className={`ui-data-grid ${className ?? ''}`} columns={columns} dataSource={rows}
    pagination={false} size="small" scroll={{ x: 'max-content' }} onRow={row => row.props}
    aria-label={props['aria-label']} /> : <div className={`ui-grid-records ui-grid-records--${activeView}`} role="list" aria-label={props['aria-label']}>
      {rows.map(row => {
        const promotedHeaderFields = activeView === 'card' || headerFieldsInList
        const audit = headerCells.some((_, index) => isAudit(index)) ? <dl className={`ui-record-audit${auditInFooter ? ` ui-record-audit--footer${activeView === 'card' ? ' ui-record-audit--compact' : ''}` : ''}`}>{headerCells.map((_, index) => isAudit(index) ? field(row, index) : null)}</dl> : undefined
        const action = actionsIndex >= 0 ? row.cells[actionsIndex]?.props.children : undefined
        return <RecordCard key={row.key} role="listitem" className={`ui-grid-record ${row.props.className ?? ''}`}
        data-connection-uuid={row.props['data-connection-uuid']}
        onClick={row.props.onClick as HTMLAttributes<HTMLDivElement>['onClick']}
        onDoubleClick={row.props.onDoubleClick as HTMLAttributes<HTMLDivElement>['onDoubleClick']} onKeyDown={row.props.onKeyDown as HTMLAttributes<HTMLDivElement>['onKeyDown']} tabIndex={row.props.tabIndex}
        header={<>{promotedHeaderFields && cardHeaderLeadingFieldIndex >= 0 ? <div className="ui-grid-record-header-leading" aria-label={fieldText(headerCells[cardHeaderLeadingFieldIndex]?.props.children)}>{row.cells[cardHeaderLeadingFieldIndex]?.props.children}</div> : <RecordFieldIcon label={headerCells[identityIndex]?.props.children} />}<div className="ui-grid-record-header-title">{row.cells[identityIndex]?.props.children}</div>{promotedHeaderFields && cardHeaderFieldIndex >= 0 ? <div className="ui-grid-record-header-extra" aria-label={fieldText(headerCells[cardHeaderFieldIndex]?.props.children)}>{row.cells[cardHeaderFieldIndex]?.props.children}</div> : null}</>}
        audit={auditInFooter ? undefined : audit}
        footer={auditInFooter && audit ? <div className="ui-grid-footer-content">{audit}<div className="ui-grid-footer-action">{action}</div></div> : action}>
        <dl>{headerCells.map((headerCell, index) => {
          if (index === identityIndex || index === actionsIndex || isAudit(index) || (promotedHeaderFields && (index === cardHeaderFieldIndex || cardHiddenIndexes.has(index)))) return null
          const cell = row.cells[index]
          const { colSpan: _, rowSpan: __, children: content, ...attributes } = cell?.props ?? {}
          void _; void __
          return <div key={index} data-field-key={fieldText(headerCell.props.children)} {...attributes as HTMLAttributes<HTMLDivElement>}><dt><RecordFieldIcon label={headerCell.props.children} />{headerCell.props.children}</dt><dd>{content}</dd></div>
        })}</dl>
      </RecordCard>})}
    </div>}
  </div>
}
