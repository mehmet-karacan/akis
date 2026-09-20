export const executableKnowledgeKinds = ['LKM', 'CKM', 'IKM'] as const
export type ExecutableKnowledgeKind = typeof executableKnowledgeKinds[number]

/** Keep in step with AkisKmLanguage.example; templates are editable drafts,
 * never executable authority or replacements for published module versions. */
export function createKnowledgeModule(kind: ExecutableKnowledgeKind = 'IKM') {
  const declarations = kind === 'LKM'
    ? ['SECENEK DISTINCT BOOLEAN ISTEGE_BAGLI false YOK', 'SECENEK ORACLE_HINT SQL_HINT ISTEGE_BAGLI YOK YOK']
    : kind === 'IKM'
      ? ['SECENEK WRITE_MODE ENUM ZORUNLU ATOMIC_DELETE_INSERT APPEND,MERGE,TRUNCATE_LOAD,ATOMIC_DELETE_INSERT', 'SECENEK KEY_COLUMNS COLUMN_LIST ISTEGE_BAGLI YOK YOK', 'SECENEK TRUNCATE_TARGET BOOLEAN ISTEGE_BAGLI false YOK', 'SECENEK ORACLE_HINT SQL_HINT ISTEGE_BAGLI YOK YOK'] : []
  const steps = kind === 'LKM'
    ? ['ADIM HAZIRLA STAGING CREATE_WORK WORK_SOURCE_1', 'ADIM AKTAR STAGING TRANSFER_JDBC WORK_SOURCE_1', 'ADIM MUHURLE STAGING SEAL_WORK WORK_SOURCE_1']
    : kind === 'IKM' ? ['ADIM HEDEFE_YAZ TARGET ATOMIC_REPLACE WORK_SOURCE_1'] : ['ADIM BOSLUK_KONTROL STAGING CHECK_NOT_NULL WORK_SOURCE_1']
  return { kmType: kind, language: 'AKIS_KM/2', source: ['AKIS_KM/2', `MODUL ${kind}`, ...declarations, ...steps, ''].join('\n'), tasks: [], options: [], optionSchema: [], ui: { optionPresentation: {} } }
}
