import { render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../../core/i18n'
import { topologyApi } from '../../topology/api'
import { StructuredDraftEditor } from '../StructuredDraftEditor'
import { selectAntOption } from '../../../test/selectAntOption'

beforeEach(async () => { vi.restoreAllMocks(); await i18n.changeLanguage('en') })

it('loads project logical schemas and preserves the selected schema in variable content', async () => {
  vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([{ uuid: 'ls1', code: 'SOURCE', name: 'Source Database', status: 'ACTIVE' }])
  const change = vi.fn()
  render(<StructuredDraftEditor projectUuid="p1" type="VARIABLE" value={{ valueSource: 'REFRESH_QUERY', query: 'SELECT SYSDATE - 1 FROM DUAL', historyMode: 'ALL' }} onChange={change} />)
  await selectAntOption(screen.getByRole('combobox', { name: 'Logical Schema' }), 'Source Database (SOURCE)')
  expect(change).toHaveBeenCalledWith(expect.objectContaining({ logicalSchemaUuid: 'ls1', historyMode: 'ALL' }))
  expect(topologyApi.listLogicalSchemas).toHaveBeenCalledWith('p1')
})

it('shows a load failure instead of silently showing an empty schema list', async () => {
  vi.spyOn(topologyApi, 'listLogicalSchemas').mockRejectedValue(new Error('offline'))
  render(<StructuredDraftEditor projectUuid="p1" type="VARIABLE" value={{ valueSource: 'REFRESH_QUERY' }} onChange={vi.fn()} />)
  expect(await screen.findByText('Logical schemas could not be loaded.')).toBeInTheDocument()
})

it('does not display an unstored sample query when the variable has no query', async () => {
  vi.spyOn(topologyApi, 'listLogicalSchemas').mockResolvedValue([])
  const change = vi.fn()
  render(<StructuredDraftEditor projectUuid="p1" type="VARIABLE" value={{ valueSource: 'REFRESH_QUERY' }} onChange={change} />)
  expect(screen.getByRole('textbox')).toHaveValue('')
  expect(change).not.toHaveBeenCalled()
  await screen.findByText('The run supplies the environment. The query uses this schema’s mapped connection.')
})
