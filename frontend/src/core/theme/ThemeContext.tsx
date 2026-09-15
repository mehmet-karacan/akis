import { createContext, useContext, useEffect, useMemo, useState, type PropsWithChildren } from 'react'

export type ThemeMode = 'light' | 'dark' | 'system'

interface ThemeState {
  mode: ThemeMode
  setMode(mode: ThemeMode): void
}

const ThemeContext = createContext<ThemeState | null>(null)

export function ThemeProvider({ children }: PropsWithChildren) {
  const [mode, setMode] = useState<ThemeMode>(() => {
    const stored = localStorage.getItem('akis.theme')
    return stored === 'dark' || stored === 'system' ? stored : 'light'
  })

  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)')
    const apply = () => {
      const resolved = mode === 'system' ? (media.matches ? 'dark' : 'light') : mode
      document.documentElement.dataset.theme = resolved
      document.querySelector('meta[name="theme-color"]')?.setAttribute(
        'content', resolved === 'dark' ? '#10181b' : '#f4f6f8',
      )
    }
    apply()
    media.addEventListener('change', apply)
    localStorage.setItem('akis.theme', mode)
    return () => media.removeEventListener('change', apply)
  }, [mode])

  const value = useMemo(() => ({ mode, setMode }), [mode])
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  const context = useContext(ThemeContext)
  if (!context) throw new Error('useTheme must be used within ThemeProvider')
  return context
}

/** Read the already resolved theme, including system mode, for third-party editors. */
export function useResolvedTheme(): 'light' | 'dark' {
  const resolve = (): 'light' | 'dark' => document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
  const [resolved, setResolved] = useState(resolve)
  useEffect(() => {
    const observer = new MutationObserver(() => setResolved(resolve()))
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    setResolved(resolve())
    return () => observer.disconnect()
  }, [])
  return resolved
}
