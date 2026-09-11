import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../../core/i18n'
import { ProjectExplorer } from '../ProjectExplorer'
import type { Definition, Folder } from '../types'

const folder: Folder = {
  uuid: 'folder-1', parentUuid: null, code: 'FINANCE', type: 'GELISTIRME',
  status: 'AKTIF', name: 'Finance', description: null, version: 1,
}
const definition: Definition = {
  uuid: 'definition-1', folderUuid: folder.uuid, type: 'PROCEDURE', code: 'LOAD_LEDGER',
  status: 'TASLAK', name: 'Load ledger', description: null, version: 1,
}

describe('project explorer UI', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('shows nested definitions and exposes named folder actions', () => {
    const select = vi.fn()
    const createFolder = vi.fn()
    render(<ProjectExplorer folders={[folder]} definitions={[definition]} selectedUuid={null} onSelect={select} onCreateFolder={createFolder} onMoveFolder={vi.fn()} />)

    expect(screen.getByRole('navigation', { name: 'Project explorer' })).toBeInTheDocument()
    expect(screen.getByText('Finance')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Load ledger/ }))
    fireEvent.click(screen.getByRole('button', { name: 'New folder' }))

    expect(select).toHaveBeenCalledWith('definition-1')
    expect(createFolder).toHaveBeenCalledOnce()
  })
})
