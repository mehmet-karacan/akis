import { beforeEach, expect, it, vi } from 'vitest'
import { topologyApi, type Model } from '../topology/api'
import { matchesCatalogReference, modelPath, resolveModelReference } from './modelRoutes'

const model: Model = { uuid: '90c24635-4184-41ed-8233-ddccf8ed15b6', logicalSchemaUuid: 'schema', code: 'SRC_UPSTREAM_PROD', name: 'SOURCE UPSTREAM_SCHEMA PROD', status: 'AKTIF', version: 1 }

beforeEach(() => vi.restoreAllMocks())

it('builds readable model URLs and resolves model codes to internal UUID records', async () => {
  vi.spyOn(topologyApi, 'listModels').mockResolvedValue([model])
  expect(modelPath(model)).toBe('/project/models/SRC_UPSTREAM_PROD')
  await expect(resolveModelReference('project', 'SRC_UPSTREAM_PROD')).resolves.toEqual(model)
  expect(topologyApi.listModels).toHaveBeenCalledWith('project')
})

it('keeps legacy UUID routes working and accepts catalog code references', async () => {
  vi.spyOn(topologyApi, 'getModel').mockResolvedValue(model)
  await expect(resolveModelReference('project', model.uuid)).resolves.toEqual(model)
  expect(matchesCatalogReference({ uuid: 'object-1', code: 'BEYAN_DOSYASI' }, 'BEYAN_DOSYASI')).toBe(true)
  expect(matchesCatalogReference({ uuid: 'object-1', code: 'BEYAN_DOSYASI' }, 'object-1')).toBe(true)
})
