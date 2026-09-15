import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { OracleConnectionCreateForm } from './OracleConnectionCreateForm'
import { topologyApi } from './api'
import { getTopologyCopy } from './copy'

describe('Oracle connection creation', () => {
  it('shows provider-specific fields, allows saving without a test, and invalidates stale test results', async () => {
    vi.spyOn(topologyApi, 'testOracleDraftConnection').mockResolvedValue({
      connected: true, oracle19cCompatible: true, databaseProduct: 'Oracle', databaseVersion: 'Oracle Database 19c',
      databaseMajorVersion: 19, databaseMinorVersion: 0, driverName: 'Oracle JDBC driver', driverVersion: '23',
    })
    const create = vi.spyOn(topologyApi, 'createOracleConnection').mockResolvedValue({
      connection: { uuid: 'connection-1' },
      initialVersion: { uuid: 'version-1', mode: 'JDBC' },
    } as Awaited<ReturnType<typeof topologyApi.createOracleConnection>>)
    render(<OracleConnectionCreateForm projectUuid="project" copy={getTopologyCopy('tr')} onConnectionCreated={vi.fn()} onClose={vi.fn()} />)

    expect(screen.getByLabelText('Sağlayıcı *')).toBeInTheDocument()
    expect(screen.queryByLabelText('Sunucu *')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Sağlayıcı *'), { target: { value: 'ORACLE' } })
    expect(screen.getByLabelText('Sunucu *')).toBeInTheDocument()
    expect(screen.getByLabelText('Port *')).toBeInTheDocument()
    expect(screen.getByLabelText('Servis adı *')).toBeInTheDocument()
    expect(screen.getByLabelText('Kullanıcı adı *')).toBeInTheDocument()
    expect(screen.getByLabelText('Şifre *')).toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Bağlantıyı Kaydet' })
    expect(save).toBeEnabled()

    fireEvent.change(screen.getByLabelText('Ad *'), { target: { value: 'SKY' } })
    fireEvent.change(screen.getByLabelText('Kod *'), { target: { value: 'SKY' } })
    fireEvent.change(screen.getByLabelText('Sunucu *'), { target: { value: '10.0.0.8' } })
    fireEvent.change(screen.getByLabelText('Port *'), { target: { value: '1907' } })
    fireEvent.change(screen.getByLabelText('Servis adı *'), { target: { value: 'TTBP2' } })
    fireEvent.change(screen.getByLabelText('Kullanıcı adı *'), { target: { value: 'reader' } })
    fireEvent.change(screen.getByLabelText('Şifre *'), { target: { value: 'local-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Bağlantıyı Test Et' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Bağlantı doğrulandı'))
    expect(create).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Ad *'), { target: { value: 'SKY Production' } })
    expect(save).toBeEnabled()
    fireEvent.change(screen.getByLabelText('Sunucu *'), { target: { value: '10.6.86.69' } })
    expect(save).toBeEnabled()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    fireEvent.click(save)
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
  })
})
