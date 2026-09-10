import { useEffect, useState } from 'react'
import { useDefinitionsI18n } from './i18n'

interface JsonDraftEditorProps {
  value: unknown
  onChange: (value: unknown) => void
  onValidityChange?: (valid: boolean) => void
}

export function JsonDraftEditor({ value, onChange, onValidityChange }: JsonDraftEditorProps) {
  const { t } = useDefinitionsI18n()
  const [text, setText] = useState(() => JSON.stringify(value, null, 2))
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setText(JSON.stringify(value, null, 2))
    setError(null)
    onValidityChange?.(true)
  }, [value, onValidityChange])

  return (
    <div className="definition-json-editor">
      <p className="definition-help" id="definition-json-help">
        {t('jsonHelp')}
      </p>
      <textarea
        aria-describedby="definition-json-help definition-json-error"
        aria-invalid={!!error}
        aria-label={t('jsonEditor')}
        className="definition-code-field"
        spellCheck={false}
        value={text}
        onChange={(event) => {
          const nextText = event.target.value
          setText(nextText)
          try {
            const parsed = JSON.parse(nextText) as unknown
            if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
              throw new Error('The document root must be an object.')
            }
            setError(null)
            onValidityChange?.(true)
            onChange(parsed)
          } catch (caught) {
            setError(caught instanceof Error ? caught.message : t('invalidJson'))
            onValidityChange?.(false)
          }
        }}
      />
      <p className="definition-field-error" id="definition-json-error" aria-live="polite">
        {error ? `${t('invalidJson')} ${error}` : ''}
      </p>
    </div>
  )
}

