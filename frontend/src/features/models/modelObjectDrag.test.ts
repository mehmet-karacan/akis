import { expect, it } from 'vitest'
import { decodeModelObjectDrag, encodeModelObjectDrag } from './modelObjectDrag'

it('round-trips a model object drag payload and rejects malformed data', () => {
  const payload = { objectUuid: 'object', modelUuid: 'model', objectName: 'Orders', objectReference: 'APP.ORDERS', modelName: 'Sales', logicalSchemaUuid: 'logical' }
  expect(decodeModelObjectDrag(encodeModelObjectDrag(payload))).toEqual(payload)
  expect(decodeModelObjectDrag('{"objectUuid":1}')).toBeNull()
  expect(decodeModelObjectDrag('invalid')).toBeNull()
})
