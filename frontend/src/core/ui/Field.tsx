import { cloneElement, isValidElement, useId, type PropsWithChildren, type ReactElement, type ReactNode } from 'react'
import { Form } from 'antd'

interface FieldProps extends PropsWithChildren {
  label: string
  hint?: string
  error?: string
  required?: boolean
  htmlFor?: string
  actions?: ReactNode
  className?: string
}

export function Field({ label, hint, error, required, htmlFor, actions, children, className = '' }: FieldProps) {
  const generatedId = useId()
  const fieldId = htmlFor ?? generatedId
  const messageId = `${fieldId}-message`
  const control = isValidElement(children)
    ? cloneElement(children as ReactElement<{ id?: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }>, {
        id: (children.props as { id?: string }).id ?? fieldId,
        'aria-describedby': hint || error ? messageId : undefined,
        'aria-invalid': error ? true : undefined,
      })
    : children
  return (
    <Form.Item className={`ui-field ${error ? 'has-error' : ''} ${className}`.trim()} layout="vertical" htmlFor={fieldId}
      label={<span className="ui-inline-title">{label}{actions}</span>} required={required} validateStatus={error ? 'error' : undefined}
      help={error ? <small id={messageId} role="alert">{error}</small> : hint ? <small id={messageId}>{hint}</small> : undefined}>
      {control}
    </Form.Item>
  )
}
