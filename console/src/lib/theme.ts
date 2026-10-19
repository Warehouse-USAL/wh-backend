import { useSyncExternalStore } from 'react'

export type ThemeChoice = 'light' | 'dark' | 'system'

const KEY = 'console.theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'
const isChoice = (v: unknown): v is ThemeChoice => v === 'light' || v === 'dark' || v === 'system'

// Used when storage is blocked, so a choice still holds for the life of the page.
let remembered: ThemeChoice = 'system'
const listeners = new Set<() => void>()
const notify = () => listeners.forEach((l) => l())

export function getTheme(): ThemeChoice {
  try {
    const stored = localStorage.getItem(KEY)
    if (isChoice(stored)) return stored
  } catch {
    // blocked storage: fall through to the in-memory choice
  }
  return remembered
}

/**
 * "system" removes the attribute so the stylesheet's prefers-color-scheme rules decide.
 * main.tsx calls this before the first render; an inline script in index.html would avoid the
 * flash on reload better but nginx's CSP forbids inline scripts.
 */
export function applyTheme(choice: ThemeChoice): void {
  if (choice === 'system') delete document.documentElement.dataset.theme
  else document.documentElement.dataset.theme = choice
}

export function setTheme(choice: ThemeChoice): void {
  remembered = choice
  try {
    localStorage.setItem(KEY, choice)
  } catch {
    // blocked storage: the in-memory choice still applies
  }
  applyTheme(choice)
  notify()
}

/** The theme actually on screen: "system" resolves through the OS preference. */
export function resolvedTheme(choice: ThemeChoice = getTheme()): 'light' | 'dark' {
  if (choice !== 'system') return choice
  return window.matchMedia?.(DARK_QUERY).matches ? 'dark' : 'light'
}

function subscribe(onChange: () => void): () => void {
  const media = window.matchMedia?.(DARK_QUERY)
  const onStorage = (e: StorageEvent) => {
    if (e.key !== KEY && e.key !== null) return
    applyTheme(getTheme())
    onChange()
  }
  listeners.add(onChange)
  window.addEventListener('storage', onStorage)
  media?.addEventListener('change', onChange)
  return () => {
    listeners.delete(onChange)
    window.removeEventListener('storage', onStorage)
    media?.removeEventListener('change', onChange)
  }
}

const snapshot = () => `${getTheme()}|${resolvedTheme()}`

export function useTheme(): { theme: ThemeChoice; resolved: 'light' | 'dark'; setTheme: (c: ThemeChoice) => void } {
  const [theme, resolved] = useSyncExternalStore(subscribe, snapshot).split('|') as [ThemeChoice, 'light' | 'dark']
  return { theme, resolved, setTheme }
}
