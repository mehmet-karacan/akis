import { describe, expect, it } from 'vitest'
import { knowledgeStepLabel, readKnowledgeCommands, readKnowledgeSteps, writeKnowledgeCommands, writeKnowledgeSteps } from './knowledgeModuleSteps'

describe('knowledge module structured steps', () => {
  const source = 'AKIS_KM/2\nMODUL LKM\nADIM ONCEKI_CALISMAYI_TEMIZLE STAGING DROP_WORK_IF_EXISTS WORK_SOURCE_1\nADIM AKTAR STAGING TRANSFER_JDBC WORK_SOURCE_1 EGER ENABLE_LOAD\nSECENEK ENABLE_LOAD BOOLEAN ISTEGE_BAGLI true YOK'

  it('round-trips ordered steps without changing the other language declarations', () => {
    const steps = readKnowledgeSteps(source)
    expect(steps).toHaveLength(2)
    expect(steps[1]).toMatchObject({ id: 'AKTAR', operation: 'TRANSFER_JDBC', condition: 'ENABLE_LOAD' })
    const rewritten = writeKnowledgeSteps(source, [steps[1]!, steps[0]!])
    expect(rewritten.indexOf('ADIM AKTAR')).toBeLessThan(rewritten.indexOf('ADIM ONCEKI'))
    expect(rewritten).toContain('SECENEK ENABLE_LOAD BOOLEAN ISTEGE_BAGLI true YOK')
  })

  it('uses the persisted step code as the human execution label', () => {
    expect(knowledgeStepLabel('ONCEKI_CALISMAYI_TEMIZLE')).toBe('Önceki Çalışmayı Temizle')
  })

  it('keeps command blocks attached to their ordered step', () => {
    const program = 'AKIS_KM/3\nMODUL LKM\nADIM HAZIRLA STAGING CREATE_WORK WORK_SOURCE_1\nKOMUT HAZIRLA SQL\n<<<\ncreate table {{ akisRef.table("WORK", "QUALIFIED") }}\n>>>'
    const steps = readKnowledgeSteps(program)
    const commands = readKnowledgeCommands(program)
    expect(commands).toEqual([{ stepId: 'HAZIRLA', channel: 'SQL', template: 'create table {{ akisRef.table("WORK", "QUALIFIED") }}' }])
    expect(writeKnowledgeCommands(program, steps, [{ ...commands[0]!, template: 'select 1' }])).toContain('KOMUT HAZIRLA SQL\n<<<\nselect 1\n>>>')
  })
})
