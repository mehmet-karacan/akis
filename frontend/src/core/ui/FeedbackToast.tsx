import { useEffect, useState } from 'react'
import { CheckCircle2, CircleAlert, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import './records.css'

export const SUCCESS_MESSAGE_MS = 5000

export function FeedbackToast({ message, onClose, tone = 'success' }: { message: string; onClose: () => void; tone?: 'success' | 'error' }) {
  const { t } = useTranslation()
  const [paused, setPaused] = useState(false)
  useEffect(() => {
    if (!message || paused) return
    const timer = window.setTimeout(onClose, tone === 'error' ? 8000 : SUCCESS_MESSAGE_MS)
    return () => window.clearTimeout(timer)
  }, [message, onClose, paused, tone])
  if (!message) return null
  return <div className={`ui-feedback-toast ui-feedback-toast--${tone}`} role={tone === 'error' ? 'alert' : 'status'} onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)} onFocus={() => setPaused(true)} onBlur={() => setPaused(false)}>
    {tone === 'error' ? <CircleAlert size={18} aria-hidden="true" /> : <CheckCircle2 size={18} aria-hidden="true" />}<span>{message}</span><button type="button" aria-label={t('common.close')} onClick={onClose}><X size={16} /></button>
  </div>
}
