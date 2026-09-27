import { createRoot } from 'react-dom/client'
import i18n from '../../src/core/i18n'
import '../../src/styles.css'
import '../../src/core/theme/ant-design.css'
import { ThemeProvider } from '../../src/core/theme/ThemeContext'
import { AntDesignProvider } from '../../src/core/theme/AntDesignProvider'
import { ExportMenu } from '../../src/core/ui/ExportMenu'

void i18n.changeLanguage('tr').then(() => {
  createRoot(document.getElementById('root')!).render(
    <ThemeProvider><AntDesignProvider>
      <main style={{ padding: 16, minWidth: 0 }}>
        <ExportMenu projectUuid="test-project" dataset="runs" resourceId="run-history" includeDetailsDefault />
      </main>
    </AntDesignProvider></ThemeProvider>,
  )
})
