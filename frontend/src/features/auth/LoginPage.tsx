import { ArrowRight, Cable, DatabaseZap, KeyRound, Layers3, LockKeyhole, MonitorSmartphone, Rocket, ShieldCheck, UserRound, Workflow } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Alert, Input } from 'antd'
import { Button } from '../../core/ui/Button'
import { LanguageSwitcher } from '../../core/ui/LanguageSwitcher'
import { ThemeSwitcher } from '../../core/ui/ThemeSwitcher'
import { ApiProblem } from '../../core/api/client'
import { useAuth } from '../../core/auth/AuthContext'
import { DatabaseProviderIcon, databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import './login.css'

const PROVIDERS = ['ORACLE', 'POSTGRESQL'] as const

/** Sign-in: story panel with the three product promises as colored feature cards, and the access card. */
export function LoginPage() {
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const { login } = useAuth()
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

  const features = [
    { tone: 'neutral', icon: Layers3, title: t('auth.proofDesign'), text: t('auth.proofDesignText') },
    { tone: 'success', icon: ShieldCheck, title: t('auth.proofValidate'), text: t('auth.proofValidateText') },
    { tone: 'pink', icon: Rocket, title: t('auth.proofPublish'), text: t('auth.proofPublishText') },
  ] as const
  const pillars = [
    { tone: 'teal', icon: Cable, label: tr ? 'Bağlantılar' : 'Connections' },
    { tone: 'warning', icon: Workflow, label: tr ? 'Ortamlar' : 'Environments' },
    { tone: 'info', icon: MonitorSmartphone, label: tr ? 'Operasyon' : 'Operations' },
  ] as const

  return (
    <main className="login-page">
      <section className="login-story">
        <div className="login-brand"><span className="login-brand-mark"><DatabaseZap size={22} /></span><strong>Akış</strong><small>{t('auth.eyebrow')}</small></div>
        <div className="login-copy">
          <h1>{t('auth.title')}</h1>
          <p>{t('auth.description')}</p>
          <div className="login-features">
            {features.map(({ tone, icon: Icon, title, text }) => <article key={title} className={`login-feature tone-${tone}`}><span className="login-feature-icon" aria-hidden="true"><Icon size={20} /></span><div><strong>{title.trim()}</strong><p>{text}</p></div></article>)}
          </div>
          <ul className="login-pillars" aria-label={tr ? 'Çalışma alanları' : 'Workspaces'}>
            {pillars.map(({ tone, icon: Icon, label }) => <li key={label} className={`tone-${tone}`}><Icon size={15} aria-hidden="true" />{label}</li>)}
          </ul>
        </div>
        <footer className="login-story-footer">
          <small>AKIŞ / METADATA CONTROL PLANE</small>
          <span className="login-providers" aria-label={tr ? 'Desteklenen veritabanları' : 'Supported databases'}>{PROVIDERS.map((provider) => <span key={provider} className="login-provider"><DatabaseProviderIcon databaseType={provider} /><span>{databaseProviderVisual(provider).label}</span></span>)}</span>
        </footer>
      </section>

      <section className="login-panel">
        <div className="login-tools">
          <LanguageSwitcher className="compact-select" />
          <ThemeSwitcher className="compact-select" />
        </div>
        <form className="login-form login-card" onSubmit={submit}>
          <div className="form-heading">
            <div className="lock-badge"><LockKeyhole size={22} /></div>
            <div><p className="eyebrow">{t('auth.secureAccess')}</p><h2>{t('auth.signIn')}</h2></div>
          </div>
          <label>{t('auth.username')}
            <Input size="large" autoComplete="username" prefix={<UserRound size={16} className="login-field-icon login-field-icon--user" aria-hidden="true" />} value={username} onChange={(event) => setUsername(event.target.value)} required />
          </label>
          <label>{t('auth.password')}
            <Input.Password size="large" autoComplete="current-password" prefix={<KeyRound size={16} className="login-field-icon login-field-icon--key" aria-hidden="true" />} value={password} onChange={(event) => setPassword(event.target.value)} required autoFocus />
          </label>
          {error && <Alert type="error" showIcon title={error} role="alert" />}
          <Button tone="primary" className="login-submit" busy={busy} type="submit">
            {busy ? t('auth.signingIn') : t('auth.signIn')}<ArrowRight size={17} />
          </Button>
          <p className="local-notice"><ShieldCheck size={15} aria-hidden="true" />{t('auth.localNotice')}</p>
        </form>
      </section>
    </main>
  )
}
