import { useLayoutEffect, type PropsWithChildren } from 'react'
import { App, ConfigProvider, theme, type ThemeConfig } from 'antd'
import trTR from 'antd/locale/tr_TR'
import enUS from 'antd/locale/en_US'
import { useTranslation } from 'react-i18next'
import { useResolvedTheme } from './ThemeContext'

export function createAntTheme(dark: boolean): ThemeConfig {
  return {
    algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      colorPrimary: dark ? '#6a9bff' : '#2563eb', colorInfo: dark ? '#6a9bff' : '#2563eb',
      colorBgLayout: dark ? '#181e28' : '#f1f4f8',
      colorBgContainer: dark ? '#222b38' : '#ffffff',
      colorBgElevated: dark ? '#272f3d' : '#ffffff',
      colorText: dark ? '#e8edf5' : '#17243b',
      colorTextSecondary: dark ? '#aebbd0' : '#52627a',
      fontFamily: '"Segoe UI", -apple-system, BlinkMacSystemFont, Arial, sans-serif',
      fontSize: 14, fontSizeSM: 12, controlHeight: 34, borderRadius: 6,
      fontSizeHeading1: 24, fontSizeHeading2: 20, fontSizeHeading3: 17,
    },
    components: {
      Button: { primaryShadow: 'none', dangerShadow: 'none', defaultShadow: 'none' },
      Table: { cellPaddingBlock: 10, cellPaddingInline: 12 },
      Modal: { borderRadiusLG: 10 },
    },
  }
}

/** Geometry and specialized SQL/flow renderers share the Ant token source. */
function TokenBridge({ children }: PropsWithChildren) {
  const { token } = theme.useToken()
  useLayoutEffect(() => {
    const values: Record<string, string> = {
      '--bg': token.colorBgLayout, '--surface': token.colorBgContainer,
      '--surface-subtle': token.colorFillAlter, '--surface-strong': token.colorBgElevated,
      '--ink': token.colorText, '--ink-soft': token.colorTextSecondary, '--ink-faint': token.colorTextSecondary,
      '--line': token.colorBorderSecondary, '--line-strong': token.colorBorder,
      '--accent': token.colorPrimary, '--accent-hover': token.colorPrimaryHover,
      '--accent-soft': token.colorPrimaryBg, '--on-accent': '#ffffff',
      '--success': token.colorSuccessText, '--success-soft': token.colorSuccessBg, '--success-line': token.colorSuccessBorder,
      '--warning': token.colorWarningText, '--warning-soft': token.colorWarningBg, '--warning-line': token.colorWarningBorder,
      '--danger': token.colorErrorText, '--danger-soft': token.colorErrorBg, '--danger-line': token.colorErrorBorder,
      '--info': token.colorInfoText, '--info-soft': token.colorInfoBg, '--info-line': token.colorInfoBorder,
      '--font-sans': token.fontFamily, '--font-body': `${token.fontSize}px`,
      '--font-mono': '"Cascadia Code", Consolas, monospace',
      '--font-caption': `${token.fontSizeSM}px`, '--font-title': '16px', '--font-section': '17px',
      '--font-page': `${token.fontSizeHeading2}px`, '--font-metric': '26px', '--font-hero': '40px',
      '--ui-radius-card': `${token.borderRadiusLG}px`,
    }
    for (const [name, value] of Object.entries(values)) document.documentElement.style.setProperty(name, value)
    return () => { for (const name of Object.keys(values)) document.documentElement.style.removeProperty(name) }
  }, [token])
  return children
}

export function AntDesignProvider({ children }: PropsWithChildren) {
  const resolved = useResolvedTheme()
  const { i18n } = useTranslation()
  return <ConfigProvider locale={i18n.language.startsWith('tr') ? trTR : enUS} theme={createAntTheme(resolved === 'dark')}>
    <App><TokenBridge>{children}</TokenBridge></App>
  </ConfigProvider>
}
