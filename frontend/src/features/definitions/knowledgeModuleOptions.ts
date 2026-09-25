export const knowledgeOptionTypes = ['BOOLEAN', 'INTEGER', 'STRING', 'ENUM', 'SQL_HINT', 'IDENTIFIER', 'COLUMN_LIST'] as const
export type KnowledgeOptionType = typeof knowledgeOptionTypes[number]

export interface KnowledgeOptionDefinition {
  key: string
  label: string
  type: KnowledgeOptionType
  description?: string
  required?: boolean
  defaultValue?: unknown
  values?: string[]
}

const record = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}

export function readKnowledgeOptionDefinitions(value: unknown): KnowledgeOptionDefinition[] {
  if (!Array.isArray(value)) return []
  return value.flatMap(item => {
    const candidate = record(item)
    const type = String(candidate.type ?? '') as KnowledgeOptionType
    const key = String(candidate.key ?? '').trim().toUpperCase()
    if (!key.match(/^[A-Z][A-Z0-9_]{0,63}$/) || !knowledgeOptionTypes.includes(type)) return []
    return [{ key, type, label: String(candidate.label || key), description: String(candidate.description || ''), required: candidate.required === true,
      ...(candidate.defaultValue !== undefined ? { defaultValue: candidate.defaultValue } : {}),
      ...(Array.isArray(candidate.values) ? { values: candidate.values.map(String).filter(Boolean) } : {}) }]
  })
}

export function optionDefaults(definitions: KnowledgeOptionDefinition[], current: Record<string, unknown> = {}) {
  return Object.fromEntries(definitions.flatMap(definition => {
    if (current[definition.key] !== undefined) return [[definition.key, current[definition.key]]]
    if (definition.defaultValue !== undefined) return [[definition.key, definition.defaultValue]]
    if (definition.type === 'BOOLEAN') return [[definition.key, false]]
    return []
  }))
}

export function readKnowledgeOptionsFromSource(source: unknown): KnowledgeOptionDefinition[] {
  if (typeof source !== 'string' || !/^AKIS_KM\/(2|3)/.test(source)) return []
  return source.split(/\r?\n/).flatMap(line => {
    const lexical = tokenizeKnowledgeLine(line)
    if (!lexical) return []
    const tokens = lexical.map(token => token.text)
    if (tokens[0] !== 'SECENEK' || tokens.length !== 6) return []
    const key = tokens[1]!
    const rawType = tokens[2]!
    const requirement = tokens[3]!
    const rawDefault = tokens[4]!
    const rawValues = tokens[5]!
    const type = rawType as KnowledgeOptionType
    if (!knowledgeOptionTypes.includes(type) || !key.match(/^[A-Z][A-Z0-9_]{0,63}$/) || !['ZORUNLU', 'ISTEGE_BAGLI'].includes(requirement)) return []
    const absentDefault = rawDefault === 'YOK' && !lexical[4]!.quoted
    const values = rawValues === 'YOK' && !lexical[5]!.quoted ? undefined : rawValues.split(',')
    let defaultValue: unknown = absentDefault ? undefined : rawDefault
    if (type === 'BOOLEAN' && ['true', 'false'].includes(rawDefault) && !absentDefault) defaultValue = rawDefault === 'true'
    // INTEGER stays a decimal string throughout editing, JSON and publication.
    // Do not coerce through Number: values above 2^53 otherwise change silently.
    return [{ key, label: key.replaceAll('_', ' '), type, required: requirement === 'ZORUNLU', ...(defaultValue !== undefined ? { defaultValue } : {}), ...(values ? { values } : {}) }]
  })
}

function tokenizeKnowledgeLine(line: string): { text: string; quoted: boolean }[] | null {
  const tokens: { text: string; quoted: boolean }[] = []
  let index = 0
  while (index < line.length) {
    if (/\s/.test(line[index]!)) { index++; continue }
    const start = index
    if (line[index] === '"') {
      index++; let closed = false
      while (index < line.length) {
        const character = line[index++]
        if (character === '\\') index++
        else if (character === '"') { closed = true; break }
      }
      if (!closed || (index < line.length && !/\s/.test(line[index]!))) return null
      try { tokens.push({ text: JSON.parse(line.slice(start, index)), quoted: true }) } catch { return null }
    } else {
      while (index < line.length && !/\s/.test(line[index]!)) index++
      tokens.push({ text: line.slice(start, index), quoted: false })
    }
  }
  return tokens
}

export function knowledgeOptionToken(value: string): string {
  return !value || value === 'YOK' || /[\s"\\]/.test(value) ? JSON.stringify(value) : value
}

// Only replace declarations represented by the form. Invalid manually authored
// lines must remain available for correction and server-side diagnostics.
export function replaceKnowledgeOptionLines(source: string, declarations: string[], previousFormLines: string[] = []): string {
  const generated = new Set(previousFormLines.map(line => line.trim()))
  const lines = source.split(/\r?\n/).filter(line => !generated.has(line.trim()) && readKnowledgeOptionsFromSource(`AKIS_KM/3\n${line}`).length === 0)
  const moduleIndex = lines.findIndex(line => /^MODUL\s/.test(line.trim()))
  lines.splice(moduleIndex + 1, 0, ...declarations)
  return lines.join('\n')
}

export function knowledgeOptionsForContent(content: Record<string, unknown>): KnowledgeOptionDefinition[] {
  const definitions = ['AKIS_KM/2', 'AKIS_KM/3'].includes(String(content.language ?? ''))
    ? readKnowledgeOptionsFromSource(content.source)
    : readKnowledgeOptionDefinitions(content.optionSchema)
  const presentation = record(record(content.ui).optionPresentation)
  return definitions.map(option => {
    const labels = record(presentation[option.key])
    return { ...option, label: typeof labels.label === 'string' && labels.label ? labels.label : option.label,
      description: typeof labels.description === 'string' ? labels.description : option.description }
  })
}

export function knowledgeOptionPresentation(content: Record<string, unknown>, options: KnowledgeOptionDefinition[]) {
  return { ...record(content.ui), optionPresentation: Object.fromEntries(options.map(option => [option.key, { label: option.label, description: option.description ?? '' }])) }
}
