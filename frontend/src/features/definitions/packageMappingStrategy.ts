import type { PreRunPlan } from './PreRunReport'

export interface PackageMappingStrategy {
  ikmVersionUuid: string
  ikmContentHash: string
  writeMode: string
  target: string
  truncateTarget: boolean
  nonReversibleDdl: boolean
  planHash: string
}

/** Read the compiled physical plan, never an unrelated package-level write override. */
export function packageMappingStrategy(plan: PreRunPlan): PackageMappingStrategy {
  const integration = plan.modules?.integration
  const target = plan.bindings?.find((binding) => binding.role === 'HEDEF' || binding.role === 'TARGET')
  return {
    ikmVersionUuid: integration?.versionUuid ?? '',
    ikmContentHash: integration?.contentHash ?? '',
    writeMode: typeof integration?.options?.WRITE_MODE === 'string'
      ? integration.options.WRITE_MODE : '',
    target: target ? `${target.owner}.${target.objectName}` : '',
    truncateTarget: integration?.options?.TRUNCATE_TARGET === true,
    nonReversibleDdl: plan.staging?.nonReversibleDdl === true,
    planHash: plan.physicalPlanHash,
  }
}
