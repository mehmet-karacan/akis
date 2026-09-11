import { Download, LoaderCircle } from 'lucide-react'
import { useState } from 'react'
import { ApiProblem } from '../../core/api/client'
import { bundleApi } from './api'
import { useBundleI18n } from './i18n'
import { downloadJson } from './utils'
import './bundles.css'

export interface ExportProjectButtonProps {
  projectUuid: string
  projectCode: string
  className?: string
}

export function ExportProjectButton({ projectUuid, projectCode, className = '' }: ExportProjectButtonProps) {
  const { t, messageForCode } = useBundleI18n()
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState('')

  const exportProject = async () => {
    setExporting(true)
    setError('')
    try {
      const document = await bundleApi.exportProject(projectUuid)
      downloadJson(document, projectCode)
    } catch (nextError) {
      setError(nextError instanceof ApiProblem
        ? messageForCode(nextError.code, nextError.message, t('exportFailed'))
        : t('exportFailed'))
    } finally {
      setExporting(false)
    }
  }

  return (
    <span className="bundle-export-control">
      <button
        className={`bundle-button bundle-button-secondary ${className}`.trim()}
        type="button"
        onClick={() => void exportProject()}
        disabled={exporting}
        aria-describedby={error ? 'bundle-export-error' : undefined}
      >
        {exporting
          ? <LoaderCircle className="bundle-spin" aria-hidden="true" />
          : <Download aria-hidden="true" />}
        {exporting ? t('exporting') : t('exportBundle')}
      </button>
      {error ? <span id="bundle-export-error" className="bundle-inline-error" role="alert">{error}</span> : null}
    </span>
  )
}
