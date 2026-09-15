import { Database } from 'lucide-react'

interface ProviderVisual {
  label: string
  monogram: string
  tone: string
}

const PROVIDERS: Record<string, ProviderVisual> = {
  ORACLE: { label: 'Oracle', monogram: 'O', tone: 'oracle' },
  POSTGRES: { label: 'PostgreSQL', monogram: 'P', tone: 'postgresql' },
  POSTGRESSQL: { label: 'PostgreSQL', monogram: 'P', tone: 'postgresql' },
  POSTGRESQL: { label: 'PostgreSQL', monogram: 'P', tone: 'postgresql' },
  MYSQL: { label: 'MySQL', monogram: 'M', tone: 'mysql' },
  MARIADB: { label: 'MariaDB', monogram: 'M', tone: 'mariadb' },
  SQLSERVER: { label: 'SQL Server', monogram: 'S', tone: 'sqlserver' },
  MSSQL: { label: 'SQL Server', monogram: 'S', tone: 'sqlserver' },
}

export function databaseProviderVisual(databaseType: string): ProviderVisual {
  const normalized = databaseType.trim().toUpperCase().replace(/[\s_-]/g, '')
  return PROVIDERS[normalized] ?? {
    label: databaseType.trim() || 'Database',
    monogram: (databaseType.trim()[0] || 'D').toUpperCase(),
    tone: 'generic',
  }
}

export function DatabaseProviderIcon({ databaseType }: { databaseType: string }) {
  const provider = databaseProviderVisual(databaseType)

  return (
    <span
      className={`database-provider-icon database-provider-icon--${provider.tone}`}
      role="img"
      aria-label={`${provider.label} database`}
      title={provider.label}
      data-provider={provider.tone}
    >
      {provider.tone === 'oracle' ? <svg className="oracle-mark" viewBox="0 0 32 20" aria-hidden="true"><rect x="2" y="3" width="28" height="14" rx="7" fill="none" stroke="currentColor" strokeWidth="3.5" /></svg> : <Database aria-hidden="true" />}
    </span>
  )
}
