import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { applyTheme, getTheme, resolvedTheme, setTheme, useTheme } from './theme'

type Listener = () => void
function mockMedia(initial: boolean) {
  const listeners = new Set<Listener>()
  const mql = {
    matches: initial,
    addEventListener: (_: string, l: Listener) => listeners.add(l),
    removeEventListener: (_: string, l: Listener) => listeners.delete(l),
  }
  vi.stubGlobal('matchMedia', vi.fn(() => mql))
  return {
    set(v: boolean) {
      mql.matches = v
      listeners.forEach((l) => l())
    },
    listeners,
  }
}

describe('theme', () => {
  beforeEach(() => {
    localStorage.clear()
    delete document.documentElement.dataset.theme
    mockMedia(false)
  })
  afterEach(() => setTheme('system'))

  it('themeDefaultsToSystemAndPersistsAChoice', () => {
    localStorage.clear()
    expect(getTheme()).toBe('system')
    setTheme('dark')
    expect(getTheme()).toBe('dark')
    expect(localStorage.getItem('console.theme')).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    localStorage.setItem('console.theme', 'purple')
    expect(getTheme()).toBe('dark') // junk in storage is ignored
  })

  it('applyThemeSetsAndClearsTheAttribute', () => {
    applyTheme('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    applyTheme('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    applyTheme('system')
    expect(document.documentElement).not.toHaveAttribute('data-theme')
  })

  it('themeSurvivesBlockedStorage', () => {
    const boom = () => {
      throw new Error('blocked')
    }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(boom)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(boom)
    setTheme('light')
    expect(getTheme()).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')
  })

  it('resolvedThemeFollowsTheOsOnlyForSystem', () => {
    mockMedia(true)
    expect(resolvedTheme('system')).toBe('dark')
    expect(resolvedTheme('light')).toBe('light')
    expect(resolvedTheme('dark')).toBe('dark')
    vi.stubGlobal('matchMedia', undefined)
    expect(resolvedTheme('system')).toBe('light')
  })

  it('systemFollowsThePreferenceChange', () => {
    const media = mockMedia(false)
    const { result, unmount } = renderHook(() => useTheme())
    expect(result.current).toMatchObject({ theme: 'system', resolved: 'light' })
    act(() => media.set(true))
    expect(result.current.resolved).toBe('dark')
    act(() => result.current.setTheme('light'))
    expect(result.current).toMatchObject({ theme: 'light', resolved: 'light' })
    unmount()
    expect(media.listeners.size).toBe(0)
  })

  it('followsAChoiceMadeInAnotherTab', () => {
    const { result } = renderHook(() => useTheme())
    act(() => {
      localStorage.setItem('console.theme', 'dark')
      window.dispatchEvent(new StorageEvent('storage', { key: 'console.theme' }))
    })
    expect(result.current).toMatchObject({ theme: 'dark', resolved: 'dark' })
    expect(document.documentElement.dataset.theme).toBe('dark')
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'other' }))
    })
    expect(result.current.theme).toBe('dark')
  })

  it('worksWithoutMatchMedia', () => {
    vi.stubGlobal('matchMedia', undefined)
    const { result } = renderHook(() => useTheme())
    expect(result.current.resolved).toBe('light')
  })
})
