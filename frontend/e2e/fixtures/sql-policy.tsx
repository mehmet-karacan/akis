import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '../../src/core/i18n'
import '../../src/styles.css'
import { ProcedureSqlEditor } from '../../src/features/definitions/ProcedureSqlEditor'

function Harness() {
  const [sql, setSql] = useState('select id,name from target_table where id=:id')
  return <main style={{ padding: 16, width: '100%', maxWidth: '100vw', boxSizing: 'border-box' }}>
    <h1>SQL Policy Check</h1>
    <ProcedureSqlEditor projectUuid="test-project" role="TARGET" label="Target SQL" value={sql} onChange={setSql} />
  </main>
}
createRoot(document.getElementById('root')!).render(<Harness />)
