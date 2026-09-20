import { Dropdown } from 'antd'
import { useTranslation } from 'react-i18next'
import { Button } from './Button'
import { FlagIcon } from './FlagIcon'

const LANGUAGES = [
  { code: 'tr', country: 'tr' },
  { code: 'en', country: 'gb' },
] as const

/** Shared language switcher for the login screen and the app shell header. Labels always
 *  reflect the currently active UI language (e.g. "İngilizce" while Turkish is active). */
export function LanguageSwitcher({ className }: { className?: string }) {
  const { t, i18n } = useTranslation()
  const current = i18n.language === 'tr' ? 'tr' : 'en'
  const currentCountry = LANGUAGES.find(language => language.code === current)?.country ?? 'gb'

  return <Dropdown trigger={['click']} menu={{
    selectedKeys: [current],
    items: LANGUAGES.map(language => ({
      key: language.code,
      label: <span className="language-option"><FlagIcon country={language.country} />{t(`language.${language.code}`)}</span>,
      onClick: () => void i18n.changeLanguage(language.code),
    })),
  }}>
    <Button tone="ghost" type="button" className={className} aria-label={`${t('header.language')}: ${t(`language.${current}`)}`}>
      <FlagIcon country={currentCountry} /><span>{current.toUpperCase()}</span>
    </Button>
  </Dropdown>
}
