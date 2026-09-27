import { Database, Eye, Link2, Table2 } from 'lucide-react'

type DataObjectTypeIconProps = { type: string; size?: number }

const normalizedType = (type: string) => type.trim().toUpperCase()

/** Shared data-object type mark used by model trees, discovery results and details. */
export function DataObjectTypeIcon({ type, size = 16 }: DataObjectTypeIconProps) {
  const normalized = normalizedType(type)
  const Icon = normalized === 'VIEW'
    ? Eye
    : normalized === 'MATERIALIZED_VIEW'
      ? Database
      : normalized === 'SYNONYM'
        ? Link2
        : Table2
  const tone = normalized === 'VIEW'
    ? 'view'
    : normalized === 'MATERIALIZED_VIEW'
      ? 'materialized-view'
      : normalized === 'SYNONYM'
        ? 'synonym'
        : 'table'
  return <span className={`model-data-object-type-icon model-data-object-type-icon--${tone}`} title={normalized} aria-hidden="true"><Icon size={size} /></span>
}
