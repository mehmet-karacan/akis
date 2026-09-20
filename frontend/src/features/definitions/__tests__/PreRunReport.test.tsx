import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { operationsApi } from '../../operations/api'
import { PreRunReport, preRunReportMarkdown, type PreRunPreview } from '../PreRunReport'

const response: PreRunPreview = {
  plan: {
    physicalPlanHash: 'a'.repeat(64), planVersion: 1, language: 'AKIS_KM/2',
    steps: [{ id: 'LOAD', site: 'STAGING', operation: 'TRANSFER_JDBC', slot: 'WORK_SOURCE_1' }, { id: 'WRITE', site: 'TARGET', operation: 'ATOMIC_REPLACE', slot: 'WORK_SOURCE_1' }],
    columns: [{ source: { object: 'SOURCE_1', column: 'ID' }, target: { object: 'TARGET', column: 'ID' } }],
    bindings: [{ nodeCode: 'SOURCE_1', role: 'KAYNAK', owner: 'TTBP', objectName: 'HAKEDIS_TIPI' }, { nodeCode: 'TARGET', role: 'HEDEF', owner: 'INNOVA_ODI', objectName: 'STG_HAKEDIS_TIPI' }],
    staging: { owner: 'INNOVA_ODI', prefixes: { loading: 'C$_', integration: 'I$_', error: 'E$_' }, nonReversibleDdl: true, workAreaPolicy: { policy: { enabled: true, allowSameSchema: true, maxRowsPerRun: 100000 }, version: 1 } },
    options: { batchRows: 500, fetchRows: 500, maxRows: 100000, maxBytes: 268435456, allowEmptySource: false },
    modules: { loading: { kind: 'LKM', versionUuid: 'l'.repeat(36), contentHash: 'b'.repeat(64), options: { DISTINCT: false } }, integration: { kind: 'IKM', versionUuid: 'i'.repeat(36), contentHash: 'c'.repeat(64), options: { WRITE_MODE: 'TRUNCATE_LOAD', TRUNCATE_TARGET: true } } },
  },
  executionVerified: false, message: 'Execution not verified',
  sqlPreview: [{ step: 'TRANSFER_JDBC', site: 'SOURCE', owner: 'TTBP', sql: 'SELECT "SRC_1"."ID" FROM "TTBP"."HAKEDIS_TIPI" "SRC_1"' }, { step: 'ATOMIC_REPLACE', site: 'TARGET', owner: 'INNOVA_ODI', sql: 'TRUNCATE TABLE "INNOVA_ODI"."STG_HAKEDIS_TIPI"' }],
}
const props = { projectUuid: 'p', scenarioUuid: 's', environmentName: 'Test', definitionName: 'I_HAKEDIS_TIPI', tr: false }
beforeEach(() => vi.restoreAllMocks())

it('simulates only on request, opens the report and returns the precise reviewed hash', async () => {
  const api = vi.spyOn(operationsApi, 'previewStagedPlan').mockResolvedValue(response)
  const ready = vi.fn()
  render(<PreRunReport {...props} environmentUuid="e" onReady={ready} />)
  expect(api).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Simulate' }))
  expect(await screen.findByText('TRANSFER_JDBC')).toBeInTheDocument()
  expect(screen.getAllByText('TTBP.HAKEDIS_TIPI').length).toBeGreaterThan(0)
  expect(screen.getByRole('alert')).toHaveTextContent('TRUNCATE TABLE')
  expect(screen.getByRole('button', { name: 'Download Report (.md)' })).toBeInTheDocument()
  expect(screen.getByText('SQL to Execute')).toBeInTheDocument()
  expect(ready).toHaveBeenCalledWith(response.plan.physicalPlanHash)
})

it('discards a response for an environment that is no longer selected', async () => {
  let resolve!: (value: typeof response) => void
  vi.spyOn(operationsApi, 'previewStagedPlan').mockReturnValue(new Promise(done => { resolve = done }))
  const ready = vi.fn()
  const view = render(<PreRunReport {...props} environmentUuid="test" onReady={ready} />)
  fireEvent.click(screen.getByRole('button', { name: 'Simulate' }))
  view.rerender(<PreRunReport {...props} environmentUuid="prod" onReady={ready} />)
  await act(async () => resolve(response))
  await waitFor(() => expect(ready).not.toHaveBeenCalled())
  expect(screen.queryByText('TRANSFER_JDBC')).not.toBeInTheDocument()
})

it('renders the same facts into the downloadable markdown report', () => {
  const markdown = preRunReportMarkdown(response, { definitionName: 'I_HAKEDIS_TIPI', environmentName: 'Test', tr: false, generatedAt: new Date('2026-09-20T00:00:00Z') })
  expect(markdown).toContain('# Pre-Run Report — I_HAKEDIS_TIPI')
  expect(markdown).toContain('**TTBP.HAKEDIS_TIPI** — SELECT only')
  expect(markdown).toContain('Non-reversible DDL: **YES (TRUNCATE TABLE)**')
  expect(markdown).toContain('| 1 | LOAD | STAGING | TRANSFER_JDBC — Load Source into Work Area |')
  expect(markdown).toContain('## SQL to Execute')
  expect(markdown).toContain('TRUNCATE TABLE "INNOVA_ODI"."STG_HAKEDIS_TIPI"')
  expect(markdown).not.toContain('undefined')
})
