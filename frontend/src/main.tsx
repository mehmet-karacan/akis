import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './core/i18n'
import './styles.css'
import '@xyflow/react/dist/style.css'
import { App } from './app/App'
import { AuthProvider } from './core/auth/AuthContext'
import { ThemeProvider } from './core/theme/ThemeContext'
import { AntDesignProvider } from './core/theme/AntDesignProvider'
import './core/theme/ant-design.css'
import './core/theme/icons.css'
import './core/theme/buttons.css'
import './app/project-explorer.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <ThemeProvider>
        <AntDesignProvider>
        <AuthProvider>
          <App />
        </AuthProvider>
        </AntDesignProvider>
      </ThemeProvider>
    </BrowserRouter>
  </StrictMode>,
)
