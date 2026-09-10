import { createContext, useContext, useMemo, useState, type PropsWithChildren } from 'react'
import { apiRequest, setAuthorizationHeader } from '../api/client'

interface AuthState {
  username: string
  login(username: string, password: string): Promise<void>
  logout(): void
}

const AuthContext = createContext<AuthState | null>(null)

function basicHeader(username: string, password: string) {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join('')
  return `Basic ${btoa(binary)}`
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [username, setUsername] = useState('')

  const value = useMemo<AuthState>(() => ({
    username,
    async login(nextUsername, password) {
      setAuthorizationHeader(basicHeader(nextUsername, password))
      try {
        await apiRequest('/api/v1/projects')
        setUsername(nextUsername)
      } catch (error) {
        setAuthorizationHeader(null)
        throw error
      }
    },
    logout() {
      setAuthorizationHeader(null)
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
