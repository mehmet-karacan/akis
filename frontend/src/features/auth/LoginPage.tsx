import { ArrowRight, DatabaseZap, Languages, LockKeyhole, Moon, Sun } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ApiProblem } from '../../core/api/client'
import { useAuth } from '../../core/auth/AuthContext'
import { useTheme, type ThemeMode } from '../../core/theme/ThemeContext'

export function LoginPage() {
  const { t, i18n } = useTranslation()
  const { login } = useAuth()
  const { mode, setMode } = useTheme()
  const navigate = useNavigate()
  const [username, setUsername] = useState('developer')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      await login(username.trim(), password)
      navigate('/project/select')
    } catch (cause) {
      setError(cause instanceof ApiProblem ? cause.message : t('auth.connectionError'))
    } finally {
      setBusy(false)
    }
  }

  const switchLanguage = () => void i18n.changeLanguage(i18n.language === 'tr' ? 'en' : 'tr')
  const nextTheme: ThemeMode = mode === 'light' ? 'dark' : 'light'

  return (
    <main className="login-page">
      <section className="login-story">
        <div className="login-brand"><DatabaseZap size={24} /><strong>Akış</strong></div>
        <div className="login-copy">
          <p className="eyebrow">{t('auth.eyebrow')}</p>
          <h1>{t('auth.title')}</h1>
          <p>{t('auth.description')}</p>
          <div className="login-proof">
            <span>01</span><p><strong>{t('auth.proofDesign')}</strong>{t('auth.proofDesignText')}</p>
            <span>02</span><p><strong>{t('auth.proofValidate')}</strong>{t('auth.proofValidateText')}</p>
            <span>03</span><p><strong>{t('auth.proofPublish')}</strong>{t('auth.proofPublishText')}</p>
          </div>
        </div>
        <small>AKIŞ / METADATA CONTROL PLANE</small>
      </section>

      <section className="login-panel">
        <div className="login-tools">
          <button className="icon-button text-button" onClick={switchLanguage} type="button">
            <Languages size={17} />{i18n.language === 'tr' ? 'EN' : 'TR'}
          </button>
          <button className="icon-button" onClick={() => setMode(nextTheme)} type="button" aria-label={t('header.theme')}>
            {mode === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
          </button>
        </div>
        <form className="login-form" onSubmit={submit}>
          <div className="form-heading">
            <div className="lock-badge"><LockKeyhole size={20} /></div>
            <div><p className="eyebrow">{t('auth.secureAccess')}</p><h2>{t('auth.signIn')}</h2></div>
          </div>
          <label>{t('auth.username')}
            <input autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} required />
          </label>
          <label>{t('auth.password')}
            <input autoComplete="current-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} required autoFocus />
          </label>
          {error && <div className="error-banner" role="alert">{error}</div>}
          <button className="button primary login-submit" disabled={busy} type="submit">
            {busy ? t('auth.signingIn') : t('auth.signIn')}<ArrowRight size={17} />
          </button>
          <p className="local-notice"><LockKeyhole size={14} />{t('auth.localNotice')}</p>
        </form>
      </section>
    </main>
  )
}
