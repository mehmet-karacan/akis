import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../core/i18n'
import { ProjectSidebarTree } from './ProjectSidebarTree'
import type { Definition, Folder } from '../features/definitions/types'

const folder: Folder = {
  uuid: 'folder-1', parentUuid: null, code: 'LOADS', type: 'GELISTIRME',
  status: 'AKTIF', name: 'Loads', description: null, version: 1,
}
const definition: Definition = {
  uuid: 'procedure-1', folderUuid: folder.uuid, type: 'PROCEDURE', code: 'LOAD_DAILY',
  status: 'TASLAK', name: 'Load Daily', description: null, version: 1,
}

describe('persistent project sidebar tree', () => {
  beforeEach(async () => { await i18n.changeLanguage('en') })

  it('keeps draft project objects visible and opens the selected editor directly', () => {
    const navigate = vi.fn()
    render(<ProjectSidebarTree projectUuid="project-1" folders={[folder]} definitions={[definition]} selectedUuid="procedure-1" loading={false} failed={false} onNavigate={navigate} onRetry={vi.fn()} />)

    expect(screen.getByRole('region', { name: 'PROJECT OBJECTS' })).toBeInTheDocument()
    const object = screen.getByRole('button', { name: /Load Daily/ })
    expect(object).toHaveClass('is-selected')
    fireEvent.click(object)
    expect(navigate).toHaveBeenCalledWith('/projects/project-1/development?definition=procedure-1')
  })
})
