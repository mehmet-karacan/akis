import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { MappingKmOptions } from './MappingKmOptions'
import { definitionsApi } from './api'
import { createKnowledgeModule } from './knowledgeModuleTemplates'
import { DEFAULT_MAPPING } from './defaults'
import type { MappingContent } from './types'

vi.mock('./api', () => ({ definitionsApi: { listKnowledgeModuleVersions: vi.fn() } }))
const changed = vi.fn()
const content = { ...createKnowledgeModule('LKM'), source: createKnowledgeModule('LKM').source
  .replace('ISTEGE_BAGLI false', 'ISTEGE_BAGLI true')
  .replace('ADIM HAZIRLA', 'SECENEK LIMIT INTEGER ISTEGE_BAGLI 9223372036854775807 YOK\nADIM HAZIRLA'),
  ui: { optionPresentation: { LIMIT: { label: 'Row Limit', description: 'Exact integer option' } } } }
beforeEach(async () => {
  vi.clearAllMocks(); await i18n.changeLanguage('en')
  vi.mocked(definitionsApi.listKnowledgeModuleVersions).mockResolvedValue([{ definitionUuid: 'load', definitionName: 'Load', uuid: 'v1', versionNumber: 1, schemaVersion: 2, contentHash: 'hash', content, description: null, createdAt: '' }])
})
function Harness() {
  const [value, setValue] = useState<MappingContent>({ ...DEFAULT_MAPPING, modules: { loading: { versionUuid: 'v1', contentHash: 'hash' } }, moduleOptions: {} })
  return <MappingKmOptions projectUuid="project" value={value} onChange={next => { changed(next); setValue(next) }} />
}
it('shows pinned defaults even when the saved mapping has no explicit overrides', async () => {
  render(<Harness />)
  expect(await screen.findByRole('textbox', { name: 'Row Limit' })).toHaveValue('9223372036854775807')
  expect(screen.getByRole('checkbox', { name: 'Enabled' })).toBeChecked()
  expect(screen.getByText('Exact integer option')).toBeInTheDocument()
  expect(changed).not.toHaveBeenCalled()
})
it('keeps integer overrides exact and removes a cleared override instead of storing null', async () => {
  render(<Harness />)
  const input = await screen.findByRole('textbox', { name: 'Row Limit' })
  fireEvent.change(input, { target: { value: '9007199254740993' } })
  expect(changed.mock.calls.at(-1)?.[0].moduleOptions.loading.LIMIT).toBe('9007199254740993')
  expect(input).toHaveValue('9007199254740993')
  fireEvent.change(input, { target: { value: '' } })
  expect(changed.mock.calls.at(-1)?.[0].moduleOptions.loading).not.toHaveProperty('LIMIT')
  await waitFor(() => expect(input).toHaveValue('9223372036854775807'))
  fireEvent.click(screen.getByRole('checkbox', { name: 'Enabled' }))
  expect(changed.mock.calls.at(-1)?.[0].moduleOptions.loading.DISTINCT).toBe(false)
})
