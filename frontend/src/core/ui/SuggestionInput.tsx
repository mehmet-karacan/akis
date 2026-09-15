import { AutoComplete, Input } from 'antd'
import type { InputHTMLAttributes } from 'react'

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value' | 'size'> & {
  value: string; suggestions: string[]; onChange(event: { target: { value: string } }): void
}
export function SuggestionInput({ suggestions, value, onChange, ...props }: Props) {
  return <AutoComplete value={value} options={suggestions.map(item => ({ value: item }))}
    onChange={next => onChange({ target: { value: next } })} filterOption={(query, option) => String(option?.value).toLocaleLowerCase().includes(query.toLocaleLowerCase())}>
    <Input {...props} />
  </AutoComplete>
}
