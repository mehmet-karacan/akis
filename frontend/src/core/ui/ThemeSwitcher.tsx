import { Moon, Sun, SunMoon } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from './Button'
import { useTheme, type ThemeMode } from '../theme/ThemeContext'

const ICONS: Record<ThemeMode, ReactNode> = {
  light: <Sun size={16} />,
  dark: <Moon size={16} />,
  system: <SunMoon size={16} />,
}

const NEXT: Record<ThemeMode, ThemeMode> = { system: 'light', light: 'dark', dark: 'system' }

/** Shared theme control for the login screen and the app shell header: a single button that
 *  cycles Sistem -> Açık -> Koyu -> Sistem on each click, the same interaction as mkaracan.com. */
export function ThemeSwitcher({ className }: { className?: string }) {
  const { t } = useTranslation()
  const { mode, setMode } = useTheme()

  return <Button tone="ghost" type="button" className={className} onClick={() => setMode(NEXT[mode])}
    aria-label={`${t('header.theme')}: ${t(`theme.${mode}`)}`}>
    {ICONS[mode]}<span>{t(`theme.${mode}`)}</span>
  </Button>
}
