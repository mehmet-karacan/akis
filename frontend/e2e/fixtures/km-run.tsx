import { createRoot } from 'react-dom/client'
import '../../src/core/i18n'
import '../../src/styles.css'
import '../../src/core/theme/ant-design.css'
import { ThemeProvider } from '../../src/core/theme/ThemeContext'
import { AntDesignProvider } from '../../src/core/theme/AntDesignProvider'
import { KmRunDetails, type KmRunData } from '../../src/features/execution/KmRunDetails'
const data: KmRunData = {
  steps: ['CREATE_WORK', 'TRANSFER_JDBC', 'SEAL_WORK', 'CHECK_NOT_NULL', 'CHECK_UNIQUE', 'ATOMIC_REPLACE'].map((operation, i) => ({ generation: 1, ordinal: i + 1, stepCode: operation, operation, site: i === 5 ? 'TARGET' : 'STAGING', slot: 'WORK_SOURCE_1', state: 'SUCCEEDED', affectedRows: 1201, errorCode: null, startedAt: null, completedAt: null })),
  workObjects: [{ uuid: 'test', owner: 'AKIS_TEST_WORK', name: 'AKIS_LOAD_EXAMPLE', state: 'DROPPED', rows: 1201, bytes: 24020 }],
}
createRoot(document.getElementById('root')!).render(<ThemeProvider><AntDesignProvider><main style={{ padding: 16, width: '100%', minWidth: 0, boxSizing: 'border-box' }}><h1>Knowledge Module</h1><KmRunDetails data={data} /></main></AntDesignProvider></ThemeProvider>)
