import { Input } from 'antd'
import { useId } from 'react'
import { useDefinitionsI18n } from './i18n'

export function validKnowledgeInteger(value: unknown): boolean {
  if (typeof value === 'number') return Number.isSafeInteger(value)
  if (typeof value !== 'string' || !/^[+-]?\d{1,19}$/.test(value)) return false
  const integer = BigInt(value)
  return integer >= -9223372036854775808n && integer <= 9223372036854775807n
}

/** Text input intentionally avoids InputNumber's coercion/clamping on blur. */
export function KnowledgeIntegerInput({ value, onChange, label }: { value: unknown; onChange(value: string | undefined): void; label?: string }) {
  const { language } = useDefinitionsI18n()
  const id = useId()
  const empty = value == null || value === ''
  const invalid = !empty && !validKnowledgeInteger(value)
  return <span className="km-integer-input">
    <Input aria-label={label} inputMode="numeric" value={value == null ? '' : String(value)} aria-invalid={invalid}
      aria-describedby={invalid ? id : undefined} status={invalid ? 'error' : undefined}
      onChange={event => onChange(event.target.value === '' ? undefined : event.target.value)} />
    {invalid && <small id={id} role="alert">{language === 'tr'
      ? '64 bit tam sayı girin. Ondalık veya yuvarlanmış değer kullanılamaz.'
      : 'Enter a signed 64-bit integer. Fractional or rounded values are not allowed.'}</small>}
  </span>
}
