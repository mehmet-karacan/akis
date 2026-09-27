import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../core/i18n'
import { ScheduleEditorPanel } from './ScheduleEditorPanel'
import { scheduleApi } from './scheduleApi'

vi.mock('./scheduleApi', () => ({
  scheduleApi: { preview: vi.fn() },
}))

function renderEditor() {
  return render(<ScheduleEditorPanel
    open
    schedule={null}
    projectUuid="project-id"
    publications={[]}
    onClose={() => undefined}
    onSaved={async () => undefined}
  />)
}

describe('schedule preview feedback', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    await i18n.changeLanguage('tr')
  })

  it('shows cron validation errors even when no occurrence is returned', async () => {
    vi.mocked(scheduleApi.preview).mockResolvedValue({
      cronExpression: 'invalid', normalizedExpression: '', timeZone: 'Europe/Istanbul',
      serverTime: '2026-09-27T00:00:00Z', syntaxValid: false, exhausted: false,
      description: '', descriptionParts: [], nextOccurrences: [], errors: ['Cron ifadesi geçersiz.'],
    })
    renderEditor()
    expect(await screen.findByText('Cron ifadesi geçersiz.')).toBeInTheDocument()
  })

  it('shows a failed preview request rather than an empty schedule', async () => {
    vi.mocked(scheduleApi.preview).mockRejectedValue(new Error('Önizleme servisine ulaşılamadı.'))
    renderEditor()
    expect(await screen.findByText('Önizleme servisine ulaşılamadı.')).toBeInTheDocument()
  })

  it('sends the selected-zone date window to server preview', async () => {
    vi.mocked(scheduleApi.preview).mockResolvedValue({
      cronExpression: '0 0 * * * *', normalizedExpression: '0 0 * * * *', timeZone: 'Europe/Istanbul',
      serverTime: '2026-09-27T00:00:00Z', syntaxValid: true, exhausted: false,
      description: '', descriptionParts: [], nextOccurrences: [], errors: [],
    })
    renderEditor()
    const [start, end] = document.querySelectorAll<HTMLInputElement>('input[type="datetime-local"]')
    expect(start).toBeDefined()
    expect(end).toBeDefined()
    fireEvent.change(start!, { target: { value: '2026-09-27T09:30' } })
    fireEvent.change(end!, { target: { value: '2026-09-27T10:30' } })
    await waitFor(() => expect(scheduleApi.preview).toHaveBeenCalledWith(
      'project-id', '0 0 * * * *', 'Europe/Istanbul',
      '2026-09-27T06:30:00.000Z', '2026-09-27T07:30:00.000Z'))
  })
})
