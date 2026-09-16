export const MODEL_OBJECT_DRAG_TYPE = 'application/x-akis-model-object'

export interface ModelObjectDragPayload {
  objectUuid: string
  modelUuid: string
  objectName: string
  objectReference: string
  modelName: string
  logicalSchemaUuid: string
}

export function encodeModelObjectDrag(payload: ModelObjectDragPayload) {
  return JSON.stringify(payload)
}

export function decodeModelObjectDrag(value: string): ModelObjectDragPayload | null {
  try {
    const payload = JSON.parse(value) as Partial<ModelObjectDragPayload>
    return typeof payload.objectUuid === 'string'
      && typeof payload.modelUuid === 'string'
      && typeof payload.objectName === 'string'
      && typeof payload.objectReference === 'string'
      && typeof payload.modelName === 'string'
      && typeof payload.logicalSchemaUuid === 'string'
      ? payload as ModelObjectDragPayload
      : null
  } catch {
    return null
  }
}
