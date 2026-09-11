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
    fireEvent.click(screen.getByRole('button', { name: 'New Folder' }))

    expect(select).toHaveBeenCalledWith('definition-1')
    expect(createFolder).toHaveBeenCalledOnce()
  })

  it('preserves a collapsed folder after refreshed data arrives and exposes a context menu', () => {
    const move = vi.fn()
    const view = render(<ProjectExplorer folders={[folder]} definitions={[definition]} selectedUuid={null} onSelect={vi.fn()} onCreateFolder={vi.fn()} onMoveFolder={move} />)
    fireEvent.click(screen.getByRole('button', { name: 'Collapse folder' }))
    expect(screen.queryByRole('button', { name: /Load ledger/ })).not.toBeInTheDocument()
    view.rerender(<ProjectExplorer folders={[{ ...folder }]} definitions={[definition]} selectedUuid={null} onSelect={vi.fn()} onCreateFolder={vi.fn()} onMoveFolder={move} />)
    expect(screen.queryByRole('button', { name: /Load ledger/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Actions for Finance' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Move Folder' }))
    expect(move).toHaveBeenCalledWith(expect.objectContaining(folder))
  })
})
