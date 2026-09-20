import { createContext, useContext, useLayoutEffect, useMemo, useState, type PropsWithChildren } from 'react'
import { apiRequest, setAuthorizationHeader, unauthorizedEvent } from '../api/client'

interface AuthState {
  username: string
  login(username: string, password: string): Promise<void>
  logout(): void
}

const AuthContext = createContext<AuthState | null>(null)
const sessionKey = 'akis.localSession'

function basicHeader(username: string, password: string) {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join('')
  return `Basic ${btoa(binary)}`
}

export function AuthProvider({ children }: PropsWithChildren) {
  const restored = (() => {
    try { return JSON.parse(sessionStorage.getItem(sessionKey) ?? 'null') as { username: string; authorization: string } | null }
    catch { return null }
  })()
  if (restored?.authorization) setAuthorizationHeader(restored.authorization)
  const [username, setUsername] = useState(restored?.username ?? '')

  // Register before child page effects can issue their first API request.
  useLayoutEffect(() => {
    const expire = () => {
      setAuthorizationHeader(null)
      sessionStorage.removeItem(sessionKey)
      setUsername('')
    }
    window.addEventListener(unauthorizedEvent, expire)
    return () => window.removeEventListener(unauthorizedEvent, expire)
  }, [])

  const value = useMemo<AuthState>(() => ({
    username,
    async login(nextUsername, password) {
      setAuthorizationHeader(basicHeader(nextUsername, password))
      try {
        await apiRequest('/api/v1/projects')
        const authorization = basicHeader(nextUsername, password)
        sessionStorage.setItem(sessionKey, JSON.stringify({ username: nextUsername, authorization }))
        setUsername(nextUsername)
      } catch (error) {
        setAuthorizationHeader(null)
        throw error
      }
    },
    logout() {
      setAuthorizationHeader(null)
      sessionStorage.removeItem(sessionKey)
      setUsername('')
    },
  }), [username])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
