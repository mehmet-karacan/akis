import { describe, expect, it } from 'vitest'
import { initialConnectionVersionDraft, toCreateConnectionVersionRequest, validateConnectionVersionDraft } from './connectionVersionModel'

describe('Oracle connection version model', () => {
  it('serializes a service-name JDBC profile without SID', () => {
    const request = toCreateConnectionVersionRequest({
      ...initialConnectionVersionDraft, host: 'db.example', identifier: 'ORCLPDB',
      credentialReferencePath: 'AKIS_ORACLE_MAIN_CREDENTIAL',
    })
    expect(request).toMatchObject({
      mode: 'JDBC',
      jdbc: {
        connectIdentifier: { type: 'SERVICE_NAME', value: 'ORCLPDB' },
        transport: 'TCP',
        credentialProvider: 'ENV',
        credentialReferencePath: 'AKIS_ORACLE_MAIN_CREDENTIAL',
      },
    })
    expect(request).not.toHaveProperty('driverReference')
  })

  it('serializes only the selected SID', () => {
    const request = toCreateConnectionVersionRequest({
      ...initialConnectionVersionDraft, host: '10.0.0.8', identifierType: 'SID',
      identifier: 'ORCL', credentialReferencePath: 'AKIS_ORACLE_MAIN_CREDENTIAL',
    })
    expect(request).toMatchObject({
      mode: 'JDBC',
      jdbc: {
        connectIdentifier: { type: 'SID', value: 'ORCL' },
        transport: 'TCP',
      },
    })
  })

  it('allows only local component JNDI names', () => {
    expect(validateConnectionVersionDraft({ ...initialConnectionVersionDraft, mode: 'JNDI', jndiName: 'java:comp/env/jdbc/OracleMain' })).toEqual([])
    expect(validateConnectionVersionDraft({ ...initialConnectionVersionDraft, mode: 'JNDI', jndiName: 'ldap://evil/ds' })).toEqual(['jndiName'])
  })

  it('serializes JNDI separately from JDBC credentials', () => {
    const request = toCreateConnectionVersionRequest({
      ...initialConnectionVersionDraft,
      mode: 'JNDI',
      jndiName: 'java:comp/env/jdbc/OracleMain',
    })
    expect(request).toEqual({
      mode: 'JNDI',
      jndi: { name: 'java:comp/env/jdbc/OracleMain' },
      policyVersion: 2,
      executionPolicy: {
        connectTimeoutMs: 10000, readTimeoutMs: 30000,
        networkTimeoutMs: 30000, queryTimeoutSeconds: 300,
      },
    })
    expect(request).not.toHaveProperty('jdbc')
  })
})
