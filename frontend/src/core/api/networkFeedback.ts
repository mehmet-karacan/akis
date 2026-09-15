export const NETWORK_FAILURE_EVENT = 'akis:network-failure'
export const APP_FEEDBACK_EVENT = 'akis:feedback'

export interface AppFeedbackDetail {
  message: string
  tone?: 'success' | 'error'
}

export function notifyNetworkFailure() {
  window.dispatchEvent(new Event(NETWORK_FAILURE_EVENT))
}

export function notifyFeedback(message: string, tone: AppFeedbackDetail['tone'] = 'success') {
  window.dispatchEvent(new CustomEvent<AppFeedbackDetail>(APP_FEEDBACK_EVENT, { detail: { message, tone } }))
}
