/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

// Read from disk: ?raw would hand back the Tailwind-compiled stylesheet, not the authored one.
const css = readFileSync(`${process.cwd()}/src/index.css`, 'utf8')

/** Declarations (`--x: y`) of the first rule whose selector starts at `selector`. */
function declarations(selector: string, after = ''): string[] {
  const start = css.indexOf(selector, after ? css.indexOf(after) : 0)
  expect(start, selector).toBeGreaterThan(-1)
  const open = css.indexOf('{', start)
  const close = css.indexOf('}', open)
  return css
    .slice(open + 1, close)
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split(';')
    .map((d) => d.trim())
    .filter(Boolean)
}

function tokens(selector: string): Record<string, string> {
  return Object.fromEntries(declarations(selector).map((d: string) => d.split(/:\s*/, 2) as [string, string]))
}

function luminance(hex: string): number {
  const full = hex.length === 4 ? hex.replace(/[0-9a-f]/gi, '$&$&').replace('##', '#') : hex
  const [r, g, b] = [1, 3, 5]
    .map((i) => parseInt(full.slice(i, i + 2), 16) / 255)
    .map((c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function ratio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

describe('theme tokens (index.css)', () => {
  it('theExplicitDarkBlockAndThePrefersDarkBlockDeclareTheSameTokens', () => {
    const explicit = declarations(':root[data-theme="dark"]').filter((d) => !d.startsWith('color-scheme'))
    const media = declarations(':root:not([data-theme]) {', '@media (prefers-color-scheme: dark) {')
    expect(media.length).toBeGreaterThan(10)
    expect(media).toEqual(explicit)
  })

  it('focusRingAndMutedTextMeetContrastInBothThemes', () => {
    const light = tokens(':root {')
    const dark = { ...light, ...tokens(':root[data-theme="dark"]') }
    for (const t of [light, dark]) {
      expect(t['--ring']).toBe('var(--blue)')
      expect(ratio(t['--blue'], t['--bg'])).toBeGreaterThanOrEqual(3)
      expect(ratio(t['--mu'], t['--bg'])).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('sidebarHoverDiffersFromTheActiveBackgroundInBothThemes', () => {
    const light = tokens(':root {')
    const dark = { ...light, ...tokens(':root[data-theme="dark"]') }
    for (const t of [light, dark]) expect(t['--hover']).not.toBe(t['--secondary'])
  })
})
