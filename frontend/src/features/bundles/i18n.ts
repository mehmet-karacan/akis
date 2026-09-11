import { useTranslation } from 'react-i18next'

const en = {
  eyebrow: 'PROJECT BUNDLE · V1',
  exportBundle: 'Export project',
  exporting: 'Preparing export…',
  exportFailed: 'Project export could not be downloaded.',
  importTitle: 'Import project bundle',
  importDescription: 'Validate a portable project bundle, preview conflicts, then create the project.',
  chooseFile: 'Choose bundle file',
  fileHelp: 'JSON files only, up to 10 MB. File content is never shown or logged.',
  selectedFile: 'Selected file',
  replaceFile: 'Choose another file',
  invalidExtension: 'Choose a file with a .json extension.',
  emptyFile: 'The selected file is empty.',
  fileTooLarge: 'The selected file exceeds the 10 MB limit.',
  invalidJson: 'The selected file is not valid JSON.',
  invalidFormat: 'This is not an AKIS project bundle v1.',
  validating: 'Validating bundle…',
  validationPassed: 'Server validation passed.',
  validationFailed: 'Bundle validation found issues.',
  requestFailed: 'The request could not be completed.',
  importOutcomeUnknown: 'The import response was not confirmed. Check the project list before starting over or retrying.',
  conflictPolicy: 'Conflict policy',
  conflictFail: 'Fail on conflict',
  conflictFailHelp: 'Stop without importing when the project code already exists.',
  conflictRename: 'Create with a new code',
  conflictRenameHelp: 'Let the server choose an available project code.',
  dryRun: 'Run import preview',
  dryRunning: 'Running preview…',
  dryRunRequired: 'A successful preview is required before import.',
  dryRunPassed: 'Import preview passed. No changes were made.',
  importProject: 'Import project',
  confirmImportEyebrow: 'FINAL CONFIRMATION',
  confirmImportTitle: 'Confirm project import',
  confirmImportDescription: 'Import {{project}} using the verified preview? This will write project metadata.',
  confirmImport: 'Confirm import',
  cancel: 'Cancel',
  close: 'Close',
  importing: 'Importing project…',
  importComplete: 'Project imported successfully.',
  openProject: 'Open imported project',
  folders: 'Folders',
  definitions: 'Definitions',
  drafts: 'Drafts',
  versions: 'Versions',
  issues: 'Validation issues',
  issueCode: 'Code',
  issuePath: 'Path',
  issueMessage: 'Message',
  fileName: 'File name',
  fileSize: 'Size',
  bundleProject: 'Bundle project',
  securityNotice: 'Topology and secret values are not imported by bundle v1.',
  reset: 'Start over',
} as const

const tr: Record<keyof typeof en, string> = {
  eyebrow: 'PROJE PAKETİ · V1',
  exportBundle: 'Projeyi dışa aktar',
  exporting: 'Dışa aktarım hazırlanıyor…',
  exportFailed: 'Proje dışa aktarımı indirilemedi.',
  importTitle: 'Proje paketini içe aktar',
  importDescription: 'Taşınabilir proje paketini doğrulayın, çakışmaları önizleyin ve projeyi oluşturun.',
  chooseFile: 'Paket dosyası seç',
  fileHelp: 'Yalnız JSON, en fazla 10 MB. Dosya içeriği gösterilmez veya loglanmaz.',
  selectedFile: 'Seçilen dosya',
  replaceFile: 'Başka dosya seç',
  invalidExtension: '.json uzantılı bir dosya seçin.',
  emptyFile: 'Seçilen dosya boş.',
  fileTooLarge: 'Seçilen dosya 10 MB sınırını aşıyor.',
  invalidJson: 'Seçilen dosya geçerli JSON değil.',
  invalidFormat: 'Bu dosya AKIS proje paketi v1 değil.',
  validating: 'Paket doğrulanıyor…',
  validationPassed: 'Sunucu doğrulaması başarılı.',
  validationFailed: 'Paket doğrulamasında sorunlar bulundu.',
  requestFailed: 'İstek tamamlanamadı.',
  importOutcomeUnknown: 'İçe aktarma yanıtı doğrulanamadı. Baştan başlamadan veya yeniden denemeden önce proje listesini kontrol edin.',
  conflictPolicy: 'Çakışma politikası',
  conflictFail: 'Çakışmada dur',
  conflictFailHelp: 'Proje kodu zaten varsa içe aktarmadan durur.',
  conflictRename: 'Yeni kodla oluştur',
  conflictRenameHelp: 'Sunucunun kullanılabilir bir proje kodu seçmesini sağlar.',
  dryRun: 'İçe aktarım önizlemesi',
  dryRunning: 'Önizleme çalışıyor…',
  dryRunRequired: 'İçe aktarmadan önce başarılı önizleme zorunludur.',
  dryRunPassed: 'İçe aktarım önizlemesi başarılı. Değişiklik yapılmadı.',
  importProject: 'Projeyi içe aktar',
  confirmImportEyebrow: 'SON ONAY',
  confirmImportTitle: 'Proje içe aktarımını onayla',
  confirmImportDescription: '{{project}} projesi doğrulanmış önizleme ile içe aktarılsın mı? Bu işlem proje metadatasını yazacak.',
  confirmImport: 'İçe aktarmayı onayla',
  cancel: 'İptal',
  close: 'Kapat',
  importing: 'Proje içe aktarılıyor…',
  importComplete: 'Proje başarıyla içe aktarıldı.',
  openProject: 'İçe aktarılan projeyi aç',
  folders: 'Klasör',
  definitions: 'Tanım',
  drafts: 'Taslak',
  versions: 'Sürüm',
  issues: 'Doğrulama sorunları',
  issueCode: 'Kod',
  issuePath: 'Yol',
  issueMessage: 'Mesaj',
  fileName: 'Dosya adı',
  fileSize: 'Boyut',
  bundleProject: 'Paket projesi',
  securityNotice: 'Paket v1 topoloji ve secret değerlerini içe aktarmaz.',
  reset: 'Baştan başla',
}

export type BundleMessageKey = keyof typeof en

export function useBundleI18n() {
  const { i18n } = useTranslation()
  const language = i18n.resolvedLanguage === 'tr' || i18n.language.startsWith('tr') ? 'tr' : 'en'
  const messages = language === 'tr' ? tr : en
  return {
    locale: language === 'tr' ? 'tr-TR' : 'en-US',
    t: (key: BundleMessageKey) => messages[key] ?? en[key],
    messageForCode: (code: string | undefined, original: string, fallback: string) => {
      if (language !== 'tr') return original || fallback
      if (!code) return fallback
      if (code === 'BUNDLE_CONFLICT') return 'Bu proje kodu hedefte zaten var.'
      if (code === 'BUNDLE_VALIDATION_FAILED') return 'Paket doğrulaması başarısız.'
      if (code === 'BUNDLE_CHECKSUM_MISMATCH') return 'Paket checksum değeri içerikle eşleşmiyor.'
      if (code === 'CONTENT_HASH_MISMATCH') return 'Tanım içeriğinin hash değeri eşleşmiyor.'
      if (code === 'SECRET_VALUE_FORBIDDEN') return 'Proje paketinde gizli değer bulunamaz.'
      if (code === 'BUNDLE_TOO_LARGE') return 'Proje paketi 10 MB sınırını aşıyor.'
      if (code === 'PROJECT_NOT_FOUND') return 'Proje bulunamadı.'
      if (code === 'NON_CONTIGUOUS_VERSIONS') return 'Sürüm numaraları 1’den başlayarak kesintisiz olmalıdır.'
      if (code === 'SEMANTIC_VALIDATION_FAILED') return 'Tanım içeriği alan kurallarını karşılamıyor.'
      if (code.includes('LIMIT_EXCEEDED')) return 'Paket güvenlik sınırlarından birini aşıyor.'
      if (code.startsWith('UNSUPPORTED_')) return 'Paket desteklenmeyen bir biçim, sürüm veya seçenek içeriyor.'
      if (code.startsWith('INVALID_')) return 'Paket alanı geçersiz.'
      if (code.endsWith('_REQUIRED')) return 'Zorunlu paket alanı eksik.'
      if (code.startsWith('DUPLICATE_')) return 'Paket tekrarlı bir kayıt içeriyor.'
      if (code.startsWith('MISSING_')) return 'Paket referansı bulunamadı.'
      return `Paket doğrulanamadı (${code}).`
    },
  }
}
