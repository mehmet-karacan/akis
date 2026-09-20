import { DataGrid } from '../../core/ui/DataGrid'
import { useTranslation } from 'react-i18next'
import type { SchemaSnapshotColumn } from '../topology/api'
import { columnSize } from '../topology/columnPresentation'

export function DataObjectTable({ columns }: { columns: SchemaSnapshotColumn[] }) {
  const { t } = useTranslation()
  return <div className="model-table-wrap"><DataGrid><thead><tr><th>{t('models.columnName')}</th><th>{t('models.dataType')}</th><th>{t('models.size')}</th><th>{t('models.nullable')}</th></tr></thead>
    <tbody>{columns.map((column) => <tr key={column.reference}><td><strong>{column.reference}</strong></td><td><code>{column.producerType}</code></td><td>{columnSize(column)}</td><td>{column.nullable ? t('models.yes') : t('models.no')}</td></tr>)}</tbody>
  </DataGrid></div>
}
