import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP_FEEDBACK_EVENT, NETWORK_FAILURE_EVENT, type AppFeedbackDetail } from '../api/networkFeedback'
import { FeedbackToast } from './FeedbackToast'

export function NetworkFeedback() {
  const [feedback, setFeedback] = useState<AppFeedbackDetail | null>(null)
  const lastShown = useRef(0)
  const { i18n } = useTranslation()
  const close = useCallback(() => setFeedback(null), [])
  useEffect(() => {
    const show = () => {
      if (Date.now() - lastShown.current < 10000) return
      lastShown.current = Date.now()
      setFeedback({ tone: 'error', message: i18n.language.startsWith('tr')
        ? 'Uygulama sunucusuna ulaşılamıyor. Bağlantınızı kontrol edip yeniden deneyin.'
        : 'Cannot reach the application server. Check your connection and try again.' })
    }
    const showFeedback = (event: Event) => setFeedback((event as CustomEvent<AppFeedbackDetail>).detail)
    window.addEventListener(NETWORK_FAILURE_EVENT, show)
    window.addEventListener(APP_FEEDBACK_EVENT, showFeedback)
    return () => {
      window.removeEventListener(NETWORK_FAILURE_EVENT, show)
      window.removeEventListener(APP_FEEDBACK_EVENT, showFeedback)
    }
  }, [i18n.language])
  return <FeedbackToast tone={feedback?.tone} message={feedback?.message ?? ''} onClose={close} />
}
