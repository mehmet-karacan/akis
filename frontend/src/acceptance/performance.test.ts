import { describe, expect, it } from 'vitest'
import { buildFolderTree } from '../app/ProjectObjectTreeAdapter'
import { pageProcedureTasks } from '../features/definitions/ProcedureEditor'
import { filterMappingRows } from '../features/definitions/mappingUtils'
import { packageValidation, type PackageContent } from '../features/definitions/packageGraph'
import type { ColumnMapping, Folder, ProcedureTask } from '../features/definitions/types'

function p95(samples: number[]) { return [...samples].sort((a, b) => a - b)[Math.ceil(samples.length * .95) - 1] ?? Infinity }
function measure(action: () => void) { const samples: number[] = []; action(); for (let i = 0; i < 20; i += 1) { const start = performance.now(); action(); samples.push(performance.now() - start) } return p95(samples) }

describe('large repository interaction budgets', () => {
  it('keeps representative client projections inside their p95 budgets', () => {
    const folders: Folder[] = Array.from({ length: 10_000 }, (_, index) => ({ uuid: `F${index}`, parentUuid: null, code: `F${index}`, status: 'AKTIF', name: `Folder ${index}`, description: null, version: 1 }))
    const tasks = Array.from({ length: 10_000 }, (_, index) => ({ id: `T${index}`, name: `Task ${index}` })) as ProcedureTask[]
    const rows: ColumnMapping[] = Array.from({ length: 1_000 }, (_, index) => ({ source: { dataset: 'S', column: `S${index}` }, target: { dataset: 'T', column: `T${index}` } }))
    const steps = Array.from({ length: 500 }, (_, index) => ({ id: `S${index}`, type: 'PROCEDURE' as const }))
    const packageContent: PackageContent = { firstStepId: 'S0', steps, transitions: steps.slice(1).map((step, index) => ({ fromStepId: `S${index}`, toStepId: step.id, outcome: 'SUCCESS' })) }

    const metrics = {
      projectTreeMs: measure(() => { buildFolderTree(folders) }),
      procedureWindowMs: measure(() => { pageProcedureTasks(tasks, '', 99) }),
      mappingFilterMs: measure(() => { filterMappingRows(rows, 't999') }),
      packageValidationMs: measure(() => { packageValidation(packageContent) }),
    }
    process.stdout.write(`AKIS_UI_P95 ${JSON.stringify(metrics)}\n`)
    expect(metrics.projectTreeMs).toBeLessThan(300)
    expect(metrics.procedureWindowMs).toBeLessThan(200)
    expect(metrics.mappingFilterMs).toBeLessThan(100)
    expect(metrics.packageValidationMs).toBeLessThan(2_000)
  })
})
