import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import i18n from '../../src/core/i18n'
import '../../src/styles.css'
import '@xyflow/react/dist/style.css'
import '../../src/features/definitions/definitions.css'
import '../../src/core/theme/ant-design.css'
import { ThemeProvider } from '../../src/core/theme/ThemeContext'
import { AntDesignProvider } from '../../src/core/theme/AntDesignProvider'
import { MappingGrid } from '../../src/features/definitions/MappingGrid'
import { ProcedureEditor } from '../../src/features/definitions/ProcedureEditor'
import { KnowledgeModuleEditor } from '../../src/features/definitions/KnowledgeModuleEditor'
import { createKnowledgeModule } from '../../src/features/definitions/knowledgeModuleTemplates'
import { VariableTestPanel } from '../../src/features/definitions/VariableTestPanel'
import { ProjectAccessProvider } from '../../src/core/auth/ProjectAccessContext'
import { ProjectSidebarTree } from '../../src/app/ProjectSidebarTree'
import { DEFAULT_MAPPING, DEFAULT_PROCEDURE } from '../../src/features/definitions/defaults'
import type { MappingContent, ProcedureContent } from '../../src/features/definitions/types'

// Synthetic authoring only; the browser tests intercept every API request.
function Harness() {
  const editor = new URLSearchParams(location.search).get('editor')
  const procedure = editor === 'procedure'
  const [km, setKm] = useState<Record<string, unknown>>(createKnowledgeModule())
  const [variable, setVariable] = useState({ logicalSchemaUuid: 'logical', dataType: 'DATE', query: 'SELECT SYSDATE - 1 FROM DUAL' })
  const [opened, setOpened] = useState('')
  const [mapping, setMapping] = useState<MappingContent>({
    ...DEFAULT_MAPPING,
    sources: [{ ...DEFAULT_MAPPING.sources[0]!, dataObjectUuid: 'source-object', schemaSnapshotUuid: 'snapshot' }],
    target: { ...DEFAULT_MAPPING.target, dataObjectUuid: 'target-object', schemaSnapshotUuid: 'snapshot' },
  })
  const [content, setContent] = useState<ProcedureContent>({ tasks: [
    ...DEFAULT_PROCEDURE.tasks,
    { ...DEFAULT_PROCEDURE.tasks[1]!, id: 'SECOND', name: 'Second step', input: undefined },
    { ...DEFAULT_PROCEDURE.tasks[1]!, id: 'THIRD', name: 'Third step', input: undefined },
  ] })
  if (editor === 'explorer') return <ProjectAccessProvider value={{ roles: [], permissions: ['TANIM_DUZENLE'] }}>
    <div style={{ display: 'grid', gridTemplateColumns: '300px minmax(0, 1fr)', height: '100dvh' }}>
      <aside style={{ minWidth: 0, minHeight: 0, overflow: 'auto', background: 'var(--surface)' }}>
        <ProjectSidebarTree projectUuid="fixture" loading={false} failed={false} onRetry={() => undefined} selectedUuid="mapping" onNavigate={setOpened}
          folders={[{ uuid: 'flows-folder', parentUuid: null, code: 'SALES', name: 'Sales', status: 'ACTIVE', version: 1 }]}
          definitions={[{ uuid: 'mapping', folderUuid: 'flows-folder', type: 'MAPPING', code: 'LOAD', name: 'Customer Load', status: 'ACTIVE', version: 1, description: null }]} />
      </aside>
      <main style={{ padding: 16, minWidth: 0, overflow: 'auto' }}><output aria-label="Opened route">{opened}</output>
        <MappingGrid projectUuid="fixture" value={mapping} onChange={setMapping} />
      </main>
    </div>
  </ProjectAccessProvider>
  return <main style={{ padding: 16, maxWidth: '100%', boxSizing: 'border-box' }}>
    <h1>{editor === 'km' ? 'Execution Module' : editor === 'variable' ? 'Variable' : procedure ? 'Procedure' : 'Interface'}</h1>
    {editor === 'km' ? <KnowledgeModuleEditor projectUuid="fixture" value={km} onChange={value => setKm(value as Record<string, unknown>)} /> : editor === 'variable' ? <ProjectAccessProvider value={{ roles: [], permissions: ['TANIM_DUZENLE', 'CALISTIRMA_BASLAT'] }}>
      <label>Variable Query<textarea aria-label="Variable Query" value={variable.query} onChange={event => setVariable({ ...variable, query: event.target.value })} /></label>
      <VariableTestPanel projectUuid="fixture" definitionUuid="variable" content={variable} />
    </ProjectAccessProvider> : procedure ? <ProcedureEditor projectUuid="fixture" value={content} onChange={setContent} /> : <MappingGrid projectUuid="fixture" value={mapping} onChange={setMapping} />}
  </main>
}
void i18n.changeLanguage('en').then(() => createRoot(document.getElementById('root')!).render(<ThemeProvider><AntDesignProvider><Harness /></AntDesignProvider></ThemeProvider>))
