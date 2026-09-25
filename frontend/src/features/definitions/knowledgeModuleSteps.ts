export const knowledgeSites = ['SOURCE', 'STAGING', 'TARGET'] as const
export const knowledgeOperations = ['DROP_WORK_IF_EXISTS', 'CREATE_WORK', 'TRANSFER_JDBC', 'SEAL_WORK', 'CHECK_NOT_NULL', 'CHECK_UNIQUE', 'ATOMIC_REPLACE', 'DROP_WORK'] as const

export interface KnowledgeStepDefinition {
  id: string
  site: string
  operation: string
  slot: string
  condition: string
}
export interface KnowledgeCommandDefinition { stepId: string; channel: 'SQL' | 'SOURCE_SQL' | 'TARGET_SQL'; template: string }

export function normalizeKnowledgeStepId(value: string) {
  return value.toLocaleUpperCase('tr-TR').replaceAll('İ', 'I').replaceAll('Ş', 'S').replaceAll('Ğ', 'G').replaceAll('Ü', 'U').replaceAll('Ö', 'O').replaceAll('Ç', 'C').replace(/[^A-Z0-9_]/g, '_').replace(/_+/g, '_').replace(/^_+|_+$/g, '')
}

export function readKnowledgeSteps(source: string): KnowledgeStepDefinition[] {
  return source.split(/\r?\n/).flatMap(line => {
    const tokens = line.trim().split(/\s+/)
    if (tokens[0] !== 'ADIM' || tokens.length < 5) return []
    return [{ id: tokens[1]!, site: tokens[2]!, operation: tokens[3]!, slot: tokens[4]!, condition: tokens[5] === 'EGER' ? (tokens[6] ?? '') : '' }]
  })
}

export function readKnowledgeCommands(source: string): KnowledgeCommandDefinition[] {
  const lines = source.split(/\r?\n/)
  const commands: KnowledgeCommandDefinition[] = []
  for (let index = 0; index < lines.length; index++) {
    const match = lines[index]!.trim().match(/^KOMUT\s+([A-Z][A-Z0-9_]*)\s+(SQL|SOURCE_SQL|TARGET_SQL)$/)
    if (!match || lines[index + 1]?.trim() !== '<<<') continue
    const body: string[] = []
    index += 2
    while (index < lines.length && lines[index]!.trim() !== '>>>') body.push(lines[index++]!)
    if (index < lines.length) commands.push({ stepId: match[1]!, channel: match[2]! as KnowledgeCommandDefinition['channel'], template: body.join('\n') })
  }
  return commands
}

export function moveKnowledgeStep(steps: KnowledgeStepDefinition[], from: number, to: number) {
  if (from < 0 || from >= steps.length || to < 0 || to >= steps.length || from === to) return steps
  const next = [...steps]
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item!)
  return next
}

export function writeKnowledgeSteps(source: string, steps: KnowledgeStepDefinition[], suppliedCommands = readKnowledgeCommands(source)) {
  const declarations = steps.flatMap(step => {
    const line = `ADIM ${step.id} ${step.site} ${step.operation} ${step.slot}${step.condition ? ` EGER ${step.condition}` : ''}`
    const commands = suppliedCommands.filter(command => command.stepId === step.id).flatMap(command => [`KOMUT ${step.id} ${command.channel}`, '<<<', ...command.template.split('\n'), '>>>'])
    return [line, ...commands]
  })
  const lines = source.split(/\r?\n/)
  const first = lines.findIndex(line => /^\s*ADIM\s+/.test(line))
  const retained: string[] = []
  for (let index = 0; index < lines.length; index++) {
    if (/^\s*ADIM\s+/.test(lines[index]!)) continue
    if (/^\s*KOMUT\s+/.test(lines[index]!)) {
      index++
      while (index < lines.length && lines[index]!.trim() !== '>>>') index++
      continue
    }
    retained.push(lines[index]!)
  }
  if (first < 0) return [...retained, ...declarations].join('\n')
  const insertion = lines.slice(0, first).filter(line => !/^\s*ADIM\s+/.test(line)).length
  retained.splice(insertion, 0, ...declarations)
  return retained.join('\n')
}

export function writeKnowledgeCommands(source: string, steps: KnowledgeStepDefinition[], commands: KnowledgeCommandDefinition[]) {
  return writeKnowledgeSteps(source, steps, commands)
}

export function knowledgeStepLabel(code: string, locale = 'tr-TR') {
  const turkish: Record<string, string> = {
    ONCEKI: 'Önceki', CALISMAYI: 'Çalışmayı', CALISMA: 'Çalışma', TEMIZLE: 'Temizle', HAZIRLA: 'Hazırla',
    AKTAR: 'Aktar', MUHURLE: 'Mühürle', HEDEFE: 'Hedefe', YAZ: 'Yaz', ZORUNLU: 'Zorunlu',
    ALANLARI: 'Alanları', KONTROL: 'Kontrol', ET: 'Et', BENZERSIZLIGI: 'Benzersizliği', TABLOYU: 'Tabloyu',
    OLUSTUR: 'Oluştur', KAYNAKTAN: 'Kaynaktan', VERIYI: 'Veriyi', AL: 'Al', DOGRULA: 'Doğrula', ALANINI: 'Alanını',
  }
  if (locale.toLowerCase().startsWith('tr')) return code.split('_').filter(Boolean).map(word => turkish[word] ?? (word.charAt(0) + word.slice(1).toLocaleLowerCase('tr-TR'))).join(' ')
  const words = code.toLocaleLowerCase(locale).split('_').filter(Boolean)
  return words.map(word => word.charAt(0).toLocaleUpperCase(locale) + word.slice(1)).join(' ')
}
