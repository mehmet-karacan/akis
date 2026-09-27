import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useState, type PropsWithChildren } from 'react'
import { apiRequest, jsonBody, refreshCsrfToken, unauthorizedEvent } from '../api/client'

interface UserProfile {
  id: number
  uuid: string
  kullaniciKodu: string
  gorunenAd: string
}

interface AuthState {
  username: string
  loading: boolean
  login(username: string, password: string): Promise<void>
  setupPassword(token: string, newPassword: string): Promise<void>
  logout(): Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: PropsWithChildren) {
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    void apiRequest<UserProfile>('/api/v1/auth/me')
      .then((user) => { if (active) setProfile(user) })
      .catch(() => { if (active) setProfile(null) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  // Register before child page effects can issue their first API request.
  useLayoutEffect(() => {
    const expire = () => {
      setProfile(null)
      setLoading(false)
    }
    window.addEventListener(unauthorizedEvent, expire)
    return () => window.removeEventListener(unauthorizedEvent, expire)
  }, [])

  const value = useMemo<AuthState>(() => ({
    username: profile?.kullaniciKodu ?? '',
    loading,
    async login(nextUsername, password) {
      // Login is the first write after a browser/backend restart. Always get
      // a fresh token here instead of trusting a stale XSRF-TOKEN cookie.
      await refreshCsrfToken()
      const user = await apiRequest<UserProfile>('/api/v1/auth/login', {
        method: 'POST',
        ...jsonBody({ kullaniciKodu: nextUsername, parola: password }),
      })
      setProfile(user)
      setLoading(false)
    },
    async setupPassword(token, newPassword) {
      await refreshCsrfToken()
      await apiRequest('/api/v1/auth/password/setup', {
        method: 'POST',
        ...jsonBody({ token, yeniParola: newPassword }),
      })
    },
    async logout() {
      try { await apiRequest('/api/v1/auth/logout', { method: 'POST' }) }
      finally {
        setProfile(null)
        setLoading(false)
      }
    },
  }), [loading, profile])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
