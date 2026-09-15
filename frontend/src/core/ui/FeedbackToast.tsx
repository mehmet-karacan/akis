import { useEffect, useRef } from 'react'
import { notification } from 'antd'

export const SUCCESS_MESSAGE_MS = 5000

export function FeedbackToast({ message, onClose, tone = 'success' }: { message: string; onClose: () => void; tone?: 'success' | 'error' }) {
  const [api, contextHolder] = notification.useNotification({ placement: 'topRight', maxCount: 3 })
  const close = useRef(onClose)
  useEffect(() => { close.current = onClose }, [onClose])
  useEffect(() => {
    if (!message) { api.destroy('feedback'); return }
    api.open({ key: 'feedback', title: message, type: tone, role: tone === 'error' ? 'alert' : 'status',
      duration: tone === 'error' ? 8 : SUCCESS_MESSAGE_MS / 1000, pauseOnHover: true,
      onClose: () => close.current() })
    return () => api.destroy('feedback')
  }, [api, message, tone])
  return contextHolder
}
