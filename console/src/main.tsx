import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import App from './App'
import './index.css'
import { applyTheme, getTheme } from './lib/theme'

// Before the first render, so the page never paints in the wrong theme once the bundle runs.
// (An inline script in index.html would also avoid the flash while the bundle loads, but the
// CSP in nginx.conf forbids inline scripts.)
applyTheme(getTheme())

const root = document.getElementById('root')
if (root === null) throw new Error('index.html has no #root element')

createRoot(root).render(
  <StrictMode>
    <BrowserRouter basename="/console">
      <App />
    </BrowserRouter>
  </StrictMode>,
)
