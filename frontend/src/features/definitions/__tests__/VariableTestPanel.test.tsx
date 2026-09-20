import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import i18n from '../../../core/i18n'
import { ProjectAccessProvider } from '../../../core/auth/ProjectAccessContext'
import { apiRequest } from '../../../core/api/client'
import { topologyApi } from '../../topology/api'
import { selectAntOption } from '../../../test/selectAntOption'
import { VariableTestPanel, type VariableTestResult } from '../VariableTestPanel'

vi.mock('../../../core/api/client', async importOriginal => ({ ...await importOriginal<object>(), apiRequest: vi.fn() }))
const content = { valueSource: 'REFRESH_QUERY', logicalSchemaUuid: 'logical', dataType: 'DATE', query: 'SELECT SYSDATE - 2 FROM DUAL' }
const result: VariableTestResult = { id: 1, kind: 'TEST', success: true, value: '2026-09-15 12:00:00', dataType: 'DATE', durationMs: 12, errorCode: null, environment: 'Test', logicalSchema: 'Logical', createdAt: '2026-09-17T12:00:00Z' }
beforeEach(async () => {
  vi.clearAllMocks(); await i18n.changeLanguage('en')
  vi.spyOn(topologyApi,'listEnvironments').mockResolvedValue([{ uuid: 'test', code: 'TEST', name: 'Test', status: 'AKTIF', policyVersion: 1, defaultEnvironment: false }])
  vi.mocked(apiRequest).mockResolvedValue([])
})
const show = (permissions = ['TANIM_DUZENLE','CALISTIRMA_BASLAT']) => render(<ProjectAccessProvider value={{roles:[],permissions}}><VariableTestPanel projectUuid="project" definitionUuid="variable" content={content} /></ProjectAccessProvider>)
it('sends the unsaved query and selected context without saving or publishing', async () => {
  show(); await selectAntOption(screen.getByRole('combobox',{name:'Test Environment'}),'Test')
  vi.mocked(apiRequest).mockResolvedValue(result)
  fireEvent.click(screen.getByRole('button',{name:'Run and Test'}))
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/project/definitions/variable/value-tests',expect.objectContaining({method:'POST',body:JSON.stringify({logicalSchemaUuid:'logical',environmentUuid:'test',dataType:'DATE',query:content.query})})))
  expect(await screen.findByText('Variable test passed. Definition and last execution value were not changed.')).toBeInTheDocument()
  expect(screen.getAllByText('2026-09-15 12:00:00').length).toBeGreaterThan(0)
  expect(vi.mocked(apiRequest).mock.calls.every(([url]) => String(url).endsWith('/value-tests'))).toBe(true)
})
it('requires an environment and execution permission', async () => {
  show(['TANIM_DUZENLE']); expect(screen.getByRole('button',{name:'Run and Test'})).toBeDisabled()
  await selectAntOption(screen.getByRole('combobox',{name:'Test Environment'}),'Test')
  expect(screen.getByRole('button',{name:'Run and Test'})).toBeDisabled()
})
it('surfaces API failures in a toast and allows retry', async () => {
  show(); await selectAntOption(screen.getByRole('combobox',{name:'Test Environment'}),'Test')
  vi.mocked(apiRequest).mockRejectedValue(new Error('No mapped connection'))
  fireEvent.click(screen.getByRole('button',{name:'Run and Test'}))
  expect(await screen.findByText('No mapped connection')).toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('button',{name:'Run and Test'})).not.toHaveClass('ant-btn-loading'))
})
it('loads earlier history through the server cursor instead of losing records after fifty tests', async () => {
  vi.mocked(apiRequest).mockResolvedValueOnce(Array.from({length:50}, (_, index) => ({...result,id:100-index})))
  show()
  const more = await screen.findByRole('button',{name:'Load Earlier Tests'})
  vi.mocked(apiRequest).mockResolvedValueOnce([{...result,id:50}])
  fireEvent.click(more)
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/project/definitions/variable/value-tests?before=51'))
  await waitFor(() => expect(screen.queryByRole('button',{name:'Load Earlier Tests'})).not.toBeInTheDocument())
})
it('keeps a failed test visible in history with an actionable sanitized error', async () => {
  show(); await selectAntOption(screen.getByRole('combobox',{name:'Test Environment'}),'Test')
  vi.mocked(apiRequest).mockResolvedValueOnce({...result,success:false,value:null,errorCode:'VARIABLE_CREDENTIAL_UNAVAILABLE'})
  fireEvent.click(screen.getByRole('button',{name:'Run and Test'}))
  await waitFor(() => expect(screen.getAllByText('Connection credentials are unavailable. Check the connection configuration. (VARIABLE_CREDENTIAL_UNAVAILABLE)').length).toBeGreaterThan(1))
  expect(screen.getByText('Failed')).toBeInTheDocument()
})
it('marks the previous result as stale after the unsaved query changes', async () => {
  const ui = (query: string) => <ProjectAccessProvider value={{roles:[],permissions:['TANIM_DUZENLE','CALISTIRMA_BASLAT']}}><VariableTestPanel projectUuid="project" definitionUuid="variable" content={{...content, query}} /></ProjectAccessProvider>
  const rendered = render(ui(content.query))
  await selectAntOption(screen.getByRole('combobox',{name:'Test Environment'}),'Test')
  vi.mocked(apiRequest).mockResolvedValueOnce(result)
  fireEvent.click(screen.getByRole('button',{name:'Run and Test'}))
  await screen.findByText('Variable test passed. Definition and last execution value were not changed.')
  rendered.rerender(ui('SELECT SYSDATE - 3 FROM DUAL'))
  expect(screen.getByText(/result below belongs to the previous test inputs/)).toBeInTheDocument()
})

it('isolates pending tests and environment selection when navigating to another variable', async () => {
  const ui = (definitionUuid: string) => <ProjectAccessProvider value={{roles:[],permissions:['TANIM_DUZENLE','CALISTIRMA_BASLAT']}}><VariableTestPanel projectUuid="project" definitionUuid={definitionUuid} content={content} /></ProjectAccessProvider>
  const rendered = render(ui('first'))
  await selectAntOption(screen.getByRole('combobox', { name: 'Test Environment' }), 'Test')
  let complete!: (value: VariableTestResult) => void
  vi.mocked(apiRequest).mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  fireEvent.click(screen.getByRole('button', { name: 'Run and Test' }))
  rendered.rerender(ui('second'))
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/v1/projects/project/definitions/second/value-tests'))
  expect(screen.getByRole('button', { name: 'Run and Test' })).toBeDisabled()
  await act(async () => complete(result))
  expect(screen.queryByText(result.value!)).not.toBeInTheDocument()
  expect(screen.queryByText(/Variable test passed/)).not.toBeInTheDocument()
  await selectAntOption(screen.getByRole('combobox', { name: 'Test Environment' }), 'Test')
  expect(screen.getByRole('button', { name: 'Run and Test' })).toBeEnabled()
  vi.mocked(apiRequest).mockResolvedValueOnce({ ...result, id: 2, value: 'new variable result' })
  fireEvent.click(screen.getByRole('button', { name: 'Run and Test' }))
  await waitFor(() => expect(screen.getAllByText('new variable result').length).toBeGreaterThan(0))
})

it.each([undefined, null, '', '   ', 123, 'x'.repeat(20001)])('never substitutes sample SQL for a missing or invalid query (%#)', async query => {
  render(<ProjectAccessProvider value={{ roles: [], permissions: ['TANIM_DUZENLE', 'CALISTIRMA_BASLAT'] }}><VariableTestPanel projectUuid="project" definitionUuid="variable" content={{ ...content, query }} /></ProjectAccessProvider>)
  await selectAntOption(screen.getByRole('combobox', { name: 'Test Environment' }), 'Test')
  expect(screen.getByRole('button', { name: 'Run and Test' })).toBeDisabled()
  expect(screen.getByText(/No sample query is executed automatically/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Run and Test' }))
  expect(vi.mocked(apiRequest).mock.calls.every(([, options]) => options?.method !== 'POST')).toBe(true)
})

it('rejects a previously selected environment when a refresh says it is archived', async () => {
  show()
  await selectAntOption(screen.getByRole('combobox', { name: 'Test Environment' }), 'Test')
  expect(screen.getByRole('button', { name: 'Run and Test' })).toBeEnabled()
  vi.mocked(topologyApi.listEnvironments).mockResolvedValue([{ uuid: 'test', code: 'TEST', name: 'Test', status: 'ARSIV', policyVersion: 1, defaultEnvironment: false }])
  fireEvent.click(screen.getByRole('button', { name: 'Refresh History' }))
  await screen.findByText('The selected environment is no longer active. Choose an active test environment.')
  expect(screen.getByRole('button', { name: 'Run and Test' })).toBeDisabled()
  expect(vi.mocked(apiRequest).mock.calls.every(([, options]) => options?.method !== 'POST')).toBe(true)
})

it('blocks stale context after a failed refresh and recovers on retry', async () => {
  show()
  await selectAntOption(screen.getByRole('combobox', { name: 'Test Environment' }), 'Test')
  vi.mocked(topologyApi.listEnvironments).mockRejectedValueOnce(new Error('Context unavailable'))
  fireEvent.click(screen.getByRole('button', { name: 'Refresh History' }))
  await screen.findByText('Context unavailable')
  expect(screen.getByRole('button', { name: 'Run and Test' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Refresh History' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Run and Test' })).toBeEnabled())
})
