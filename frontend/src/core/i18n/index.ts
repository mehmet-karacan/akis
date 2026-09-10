import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './locales/en.json'
import tr from './locales/tr.json'

const savedLanguage = localStorage.getItem('akis.language')
const initialLanguage = savedLanguage === 'tr' ? 'tr' : 'en'

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, tr: { translation: tr } },
  lng: initialLanguage,
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

i18n.on('languageChanged', (language) => {
  const safeLanguage = language === 'tr' ? 'tr' : 'en'
  localStorage.setItem('akis.language', safeLanguage)
  document.documentElement.lang = safeLanguage
})

document.documentElement.lang = initialLanguage

export default i18n
