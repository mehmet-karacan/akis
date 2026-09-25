import { describe, expect, it } from 'vitest'
import { optionDefaults, readKnowledgeOptionDefinitions, knowledgeOptionsForContent, knowledgeOptionPresentation, knowledgeOptionToken, readKnowledgeOptionsFromSource, replaceKnowledgeOptionLines } from './knowledgeModuleOptions'
import { createKnowledgeModule } from './knowledgeModuleTemplates'
import { createDefaultContent } from './defaults'
import { initialSchemaVersion } from './mappingAuthoring'

describe('knowledge module options', () => {
  it('normalizes typed option definitions and ignores unsafe keys', () => {
    expect(readKnowledgeOptionDefinitions([{ key: ' distinct ', label: 'Distinct', type: 'BOOLEAN', defaultValue: true }, { key: 'bad-key', type: 'STRING' }])).toEqual([
      { key: 'DISTINCT', label: 'Distinct', type: 'BOOLEAN', description: '', required: false, defaultValue: true },
    ])
  })

  it('hydrates defaults without overwriting mapping values', () => {
    const definitions = readKnowledgeOptionDefinitions([{ key: 'DISTINCT', type: 'BOOLEAN', defaultValue: true }, { key: 'ORACLE_HINT', type: 'SQL_HINT', defaultValue: 'FULL(T)' }])
    expect(optionDefaults(definitions, { DISTINCT: false })).toEqual({ DISTINCT: false, ORACLE_HINT: 'FULL(T)' })
  })
  it('creates executable defaults and independent editable templates', () => {
    const content = createDefaultContent('KNOWLEDGE_MODULE') as Record<string, unknown>
    expect(content).toMatchObject({ kmType: 'IKM', language: 'AKIS_KM/3', tasks: [], options: [] })
    expect(initialSchemaVersion('KNOWLEDGE_MODULE', content)).toBe(2)
    expect(knowledgeOptionsForContent(content).map(option => option.key)).toEqual(['WRITE_MODE', 'KEY_COLUMNS', 'TRUNCATE_TARGET', 'DROP_WORK_TABLE', 'ORACLE_HINT'])
    expect(createKnowledgeModule('LKM').source).toContain('TRANSFER_JDBC')
    expect(createKnowledgeModule('CKM').source).toContain('CHECK_NOT_NULL')
    content.source = 'changed'
    expect((createDefaultContent('KNOWLEDGE_MODULE') as Record<string, unknown>).source).not.toBe('changed')
  })
  it('keeps presentation metadata separate from executable options', () => {
    const content = createKnowledgeModule('LKM')
    const options = knowledgeOptionsForContent(content).map(option => ({ ...option, label: `Label ${option.key}`, description: 'Helpful context' }))
    const edited = { ...content, ui: knowledgeOptionPresentation(content, options) }
    expect(knowledgeOptionsForContent(edited)).toEqual(options)
    expect(edited.source).toBe(content.source)
    expect(edited.optionSchema).toEqual([])
  })
  it('round trips multi-word defaults and literal YOK with bounded quoted tokens', () => {
    for (const value of ['FULL(T) PARALLEL(4)', 'Müşteri "aktif"', 'YOK', 'x\\y']) {
      const source = `AKIS_KM/2\nMODUL LKM\nSECENEK TEST STRING ISTEGE_BAGLI ${knowledgeOptionToken(value)} YOK`
      expect(readKnowledgeOptionsFromSource(source)[0]?.defaultValue).toBe(value)
    }
    expect(readKnowledgeOptionsFromSource('AKIS_KM/2\nSECENEK TEST STRING ISTEGE_BAGLI "OPEN YOK')).toEqual([])
  })
  it('preserves unparsed source declarations when a form option changes', () => {
    const malformed = 'SECENEK BROKEN STRING ISTEGE_BAGLI "OPEN YOK'
    const source = `AKIS_KM/2\nMODUL LKM\nSECENEK DISTINCT BOOLEAN ISTEGE_BAGLI false YOK\n${malformed}\n-- Keep this comment\nADIM HAZIRLA STAGING CREATE_WORK WORK_SOURCE_1`
    const updated = replaceKnowledgeOptionLines(source, ['SECENEK DISTINCT BOOLEAN ISTEGE_BAGLI true YOK'])
    expect(updated).toContain(malformed)
    expect(updated).toContain('-- Keep this comment')
    expect(updated).not.toContain('ISTEGE_BAGLI false')
    expect(updated.match(/SECENEK DISTINCT/g)).toHaveLength(1)
  })
  it('keeps large INTEGER defaults exact through source parsing, JSON and option hydration', () => {
    const source = 'AKIS_KM/2\nMODUL LKM\nSECENEK LIMIT INTEGER ISTEGE_BAGLI 9223372036854775807 YOK'
    const options = readKnowledgeOptionsFromSource(source)
    expect(options[0]?.defaultValue).toBe('9223372036854775807')
    expect(JSON.parse(JSON.stringify(optionDefaults(options)))).toEqual({ LIMIT: '9223372036854775807' })
  })
})
