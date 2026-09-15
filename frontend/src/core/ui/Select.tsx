import { Children, isValidElement, useId, useLayoutEffect, useRef, useState, type ReactNode, type SelectHTMLAttributes } from 'react'
import { Select as AntSelect } from 'antd'
import type { RefSelectProps } from 'antd'

type Choice = { value: string; label: ReactNode; disabled?: boolean }
type SelectionEvent = { target: { value: string }; currentTarget: { value: string } }
type Props = Omit<SelectHTMLAttributes<HTMLSelectElement>, 'onChange' | 'onBlur' | 'onFocus' | 'size' | 'multiple'> & {
  onChange?: (event: SelectionEvent) => void
}

function choices(children: ReactNode): Choice[] {
  return Children.toArray(children).flatMap(child => {
    if (!isValidElement<{ value?: string | number; children?: ReactNode; disabled?: boolean }>(child)) return []
    if (child.type === 'option') return [{ value: String(child.props.value ?? child.props.children ?? ''), label: child.props.children, disabled: child.props.disabled }]
    return choices(child.props.children)
  })
}

/** Shared Ant dropdown with native form serialization and required-field validation. */
export function Select({ children, value, defaultValue, onChange, name, required, disabled, id, className, style, title, ...aria }: Props) {
  const options = choices(children)
  const [localValue, setLocalValue] = useState(() => String(defaultValue ?? options.find(option => !option.disabled)?.value ?? ''))
  const [invalid, setInvalid] = useState(false)
  const ref = useRef<RefSelectProps>(null)
  const wrapper = useRef<HTMLSpanElement>(null)
  const generatedId = useId()
  const [implicitLabel, setImplicitLabel] = useState<string>()
  const [fieldsetDisabled, setFieldsetDisabled] = useState(false)
  useLayoutEffect(() => {
    const fieldset = wrapper.current?.closest('fieldset')
    if (!fieldset) return
    const update = () => setFieldsetDisabled(fieldset.disabled)
    update()
    const observer = new MutationObserver(update)
    observer.observe(fieldset, { attributes: true, attributeFilter: ['disabled'] })
    return () => observer.disconnect()
  }, [])
  useLayoutEffect(() => {
    const label = wrapper.current?.closest('label')
    if (!label) return
    const text = [...label.childNodes].filter(node => node !== wrapper.current && (node.nodeType === Node.TEXT_NODE || (node instanceof HTMLElement && node.tagName === 'SPAN'))).map(node => node.textContent).join(' ').trim()
    setImplicitLabel(text || undefined)
  }, [children])
  const selected = value === undefined ? localValue : String(value)
  return <span ref={wrapper} className="ui-select-control" style={style}>
    <AntSelect ref={ref} id={id ?? generatedId} className={className} title={title} value={selected} options={options}
      disabled={disabled || fieldsetDisabled} status={invalid || aria['aria-invalid'] ? 'error' : undefined}
      aria-label={aria['aria-label'] ?? implicitLabel} aria-labelledby={aria['aria-labelledby']}
      aria-describedby={aria['aria-describedby']} aria-required={required} aria-invalid={invalid || aria['aria-invalid']}
      showSearch={{ optionFilterProp: 'label' }} virtual={false}
      onChange={next => { setLocalValue(next); setInvalid(false); onChange?.({ target: { value: next }, currentTarget: { value: next } }) }} />
    <input className="ui-form-validation-proxy" aria-hidden="true" tabIndex={-1} name={name} required={required}
      disabled={disabled || fieldsetDisabled} value={selected} onChange={() => { /* Ant Select owns the value. */ }}
      onInvalid={event => { event.preventDefault(); setInvalid(true); ref.current?.focus() }} />
  </span>
}
