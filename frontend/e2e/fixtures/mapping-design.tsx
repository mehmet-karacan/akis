import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import '../../src/core/i18n'
import '../../src/styles.css'
import '../../src/core/theme/ant-design.css'
import { ThemeProvider } from '../../src/core/theme/ThemeContext'
import { AntDesignProvider } from '../../src/core/theme/AntDesignProvider'
import { MappingDesignAssessment } from '../../src/features/definitions/MappingDesignAssessment'
import { DEFAULT_MAPPING } from '../../src/features/definitions/defaults'

function Harness() {
  const [schema, setSchema] = useState(1)
  return <main style={{ padding: 16, maxWidth: '100%', boxSizing: 'border-box' }}>
    <h1>Mapping</h1>
    <MappingDesignAssessment projectUuid="test-project" value={{ ...DEFAULT_MAPPING, writeStrategy: { kind: 'ATOMIC_DELETE_INSERT' } }} schemaVersion={schema} onUpgrade={() => setSchema(2)} />
  </main>
}
createRoot(document.getElementById('root')!).render(<ThemeProvider><AntDesignProvider><Harness /></AntDesignProvider></ThemeProvider>)
