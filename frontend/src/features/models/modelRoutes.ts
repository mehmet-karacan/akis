import { topologyApi, type Model } from '../topology/api'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const MODEL_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,99}$/

export const modelRouteSegment = (model: Pick<Model, 'code'>) => encodeURIComponent(model.code)
export const modelPath = (model: Pick<Model, 'code'>) => `/project/models/${modelRouteSegment(model)}`

/** Resolves new human-readable model-code routes while retaining old UUID/development links. */
export async function resolveModelReference(projectUuid: string, reference: string): Promise<Model> {
  if (UUID_PATTERN.test(reference) || !MODEL_CODE_PATTERN.test(reference)) {
    return topologyApi.getModel(projectUuid, reference)
  }
  const models = await topologyApi.listModels(projectUuid)
  const model = models.find(item => item.code.toLocaleUpperCase() === reference.toLocaleUpperCase())
  if (!model) throw new Error('MODEL_NOT_FOUND')
  return model
}

export const matchesCatalogReference = (item: { uuid: string; code: string }, reference: string | null) =>
  Boolean(reference && (item.uuid === reference || item.code.toLocaleUpperCase() === reference.toLocaleUpperCase()))
