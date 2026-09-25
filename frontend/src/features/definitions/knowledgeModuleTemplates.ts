export const executableKnowledgeKinds = ['LKM', 'CKM', 'IKM'] as const
export type ExecutableKnowledgeKind = typeof executableKnowledgeKinds[number]

/** Keep in step with AkisKmLanguage.example; templates are editable drafts,
 * never executable authority or replacements for published module versions. */
export function createKnowledgeModule(kind: ExecutableKnowledgeKind = 'IKM') {
  const declarations = kind === 'LKM'
    ? ['SECENEK DISTINCT BOOLEAN ISTEGE_BAGLI false YOK', 'SECENEK ORACLE_HINT SQL_HINT ISTEGE_BAGLI YOK YOK']
    : kind === 'IKM'
      ? ['SECENEK WRITE_MODE ENUM ZORUNLU ATOMIC_DELETE_INSERT APPEND,MERGE,TRUNCATE_LOAD,ATOMIC_DELETE_INSERT', 'SECENEK KEY_COLUMNS COLUMN_LIST ISTEGE_BAGLI YOK YOK', 'SECENEK TRUNCATE_TARGET BOOLEAN ISTEGE_BAGLI false YOK', 'SECENEK DROP_WORK_TABLE BOOLEAN ISTEGE_BAGLI true YOK', 'SECENEK ORACLE_HINT SQL_HINT ISTEGE_BAGLI YOK YOK'] : []
  const steps = kind === 'LKM'
    ? ['ADIM ONCEKI_CALISMAYI_TEMIZLE STAGING DROP_WORK_IF_EXISTS WORK_SOURCE_1', 'ADIM HAZIRLA STAGING CREATE_WORK WORK_SOURCE_1', 'ADIM AKTAR STAGING TRANSFER_JDBC WORK_SOURCE_1', 'ADIM MUHURLE STAGING SEAL_WORK WORK_SOURCE_1']
    : kind === 'IKM' ? ['ADIM HEDEFE_YAZ TARGET ATOMIC_REPLACE WORK_SOURCE_1', 'ADIM DROP_WORK_TABLE STAGING DROP_WORK WORK_SOURCE_1 EGER DROP_WORK_TABLE'] : ['ADIM BOSLUK_KONTROL STAGING CHECK_NOT_NULL WORK_SOURCE_1']
  const technology = kind === 'LKM'
    ? { source: 'ORACLE', target: 'POSTGRESQL' }
    : { source: 'POSTGRESQL', target: 'POSTGRESQL' }
  const commands = kind === 'LKM' ? [
    'KOMUT ONCEKI_CALISMAYI_TEMIZLE SQL\n<<<\ndrop table {{ akisRef.table("WORK", "QUALIFIED") }}\n>>>',
    'KOMUT HAZIRLA SQL\n<<<\ncreate table {{ akisRef.table("WORK", "QUALIFIED") }} (\n{{ akisRef.columns("TARGET", "DDL", ",\\n") }}\n)\n>>>',
    'KOMUT AKTAR SOURCE_SQL\n<<<\n{{ akisRef.context("SOURCE_SELECT") }}\n>>>',
    'KOMUT AKTAR TARGET_SQL\n<<<\n{{ akisRef.context("STAGING_INSERT") }}\n>>>',
    'KOMUT MUHURLE SQL\n<<<\nselect count(*) from {{ akisRef.table("WORK", "QUALIFIED") }}\n>>>',
  ] : kind === 'IKM' ? [
    'KOMUT HEDEFE_YAZ SQL\n<<<\n{{ akisRef.integration("WRITE_MODE") }}\n>>>',
    'KOMUT DROP_WORK_TABLE SQL\n<<<\ndrop table {{ akisRef.table("WORK", "QUALIFIED") }}\n>>>',
  ] : ['KOMUT BOSLUK_KONTROL SQL\n<<<\n{{ akisRef.check("NOT_NULL") }}\n>>>']
  const program = steps.flatMap(step => {
    const id = step.split(/\s+/)[1]
    return [step, ...commands.filter(command => command.startsWith(`KOMUT ${id} `))]
  })
  return { kmType: kind, language: 'AKIS_KM/3', technology, source: ['AKIS_KM/3', `MODUL ${kind}`, ...declarations, ...program, ''].join('\n'), tasks: [], options: [], optionSchema: [], ui: { optionPresentation: {} } }
}
