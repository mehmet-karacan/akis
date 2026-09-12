import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { OracleConnectionCreateForm } from './OracleConnectionCreateForm'
import { topologyApi } from './api'
import { getTopologyCopy } from './copy'

describe('Oracle connection creation', () => {
  it('shows provider-specific fields together and requires a fresh test before save', async () => {
    vi.spyOn(topologyApi, 'testOracleDraftConnection').mockResolvedValue({
      connected: true, oracle19cCompatible: true, databaseProduct: 'Oracle', databaseVersion: 'Oracle Database 19c',
      databaseMajorVersion: 19, databaseMinorVersion: 0, driverName: 'Oracle JDBC driver', driverVersion: '23',
    })
    const create = vi.spyOn(topologyApi, 'createOracleConnection')
    render(<OracleConnectionCreateForm projectUuid="project" copy={getTopologyCopy('tr')} locale="tr-TR" onConnectionCreated={vi.fn()} onClose={vi.fn()} />)

    expect(screen.getByText('Sağlayıcı seçimi')).toBeInTheDocument()
    expect(screen.getByLabelText('Sunucu *')).toBeInTheDocument()
    expect(screen.getByLabelText('Port *')).toBeInTheDocument()
    expect(screen.getByLabelText('Servis adı *')).toBeInTheDocument()
    expect(screen.getByLabelText('Kimlik bilgisi ortam değişkeni *')).toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Bağlantıyı Kaydet' })
    expect(save).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Ad *'), { target: { value: 'SKY' } })
    fireEvent.change(screen.getByLabelText('Kod *'), { target: { value: 'SKY' } })
    fireEvent.change(screen.getByLabelText('Sunucu *'), { target: { value: '10.6.86.68' } })
    fireEvent.change(screen.getByLabelText('Port *'), { target: { value: '1907' } })
    fireEvent.change(screen.getByLabelText('Servis adı *'), { target: { value: 'TTBP2' } })
    fireEvent.change(screen.getByLabelText('Kimlik bilgisi ortam değişkeni *'), { target: { value: 'AKIS_ORACLE_SOURCE_CREDENTIAL' } })
    fireEvent.click(screen.getByRole('button', { name: 'Bağlantıyı Test Et' }))

    await waitFor(() => expect(save).toBeEnabled())
    expect(create).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Sunucu *'), { target: { value: '10.6.86.69' } })
    expect(save).toBeDisabled()
  })
})
