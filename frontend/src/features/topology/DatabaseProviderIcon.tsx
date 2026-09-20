import { Database } from 'lucide-react'
import oracleLogo from './assets/oracle-logo.svg'
import postgresLogo from './assets/postgresql-logo.svg'

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
      {provider.tone === 'oracle' ? <img className="oracle-mark" src={oracleLogo} alt="" aria-hidden="true" /> : provider.tone === 'postgresql' ? <img className="postgres-mark" src={postgresLogo} alt="" aria-hidden="true" /> : <Database aria-hidden="true" />}
    </span>
  )
}
