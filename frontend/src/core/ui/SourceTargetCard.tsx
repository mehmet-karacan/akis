import { Database, DatabaseZap } from 'lucide-react'

export interface SourceTargetCardProps {
  role: 'SOURCE' | 'TARGET'
  label: string
  name: string
  technicalAlias?: string
  className?: string
}

/** Shared source/target identity block used by mapping inspectors. */
export function SourceTargetCard({ role, label, name, technicalAlias, className = '' }: SourceTargetCardProps) {
  const Icon = role === 'SOURCE' ? DatabaseZap : Database
  return (
    <div className={`mapping-inspector-identity ${className}`.trim()}>
      <span className={`procedure-heading-icon procedure-heading-icon--${role.toLowerCase()}`} aria-hidden="true">
        <Icon size={17} />
      </span>
      <div>
        <span>{label}</span>
        <strong>{name}</strong>
        {technicalAlias && technicalAlias !== name && <small className="mapping-inspector-technical-alias">{technicalAlias}</small>}
      </div>
    </div>
  )
}
