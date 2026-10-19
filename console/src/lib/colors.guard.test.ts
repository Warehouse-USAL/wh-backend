import { describe, expect, it } from 'vitest'

// Lint-style guard: colours come from the theme tokens in index.css (bg-bg, text-mu, border-bd,
// --s-* for series ...), never from literals, or a page breaks in one of the two themes.
const FILES = import.meta.glob<string>(['/src/**/*.{ts,tsx,css}', '!/src/**/*.test.{ts,tsx}'], {
  query: '?raw',
  import: 'default',
  eager: true,
})

/** Files and directories exempt from the guard (paths relative to src/). */
const ALLOWED = [
  'index.css', // defines the tokens
  'components/ui/', // vendored coss ui; its dark: variants and black/white alphas are theme-aware
  'lib/segmented-control.ts', // vendored with coss ui
  'test/',
  'sample/', // sample-mode data, not styling
  '__fixtures__/',
]

const NAMED = '(?:white|black|red|green|blue|gray|grey|orange|yellow|purple|pink|teal|cyan|magenta|silver|navy|maroon|lime|olive|aqua|fuchsia|brown|gold|indigo|violet)'

/** Code part of a line: URLs and comments cannot hold a colour, so `see issue #bad` or `//x.y/#fff` are not offences. */
const code = (line: string) =>
  line
    .replace(/[a-z][a-z0-9+.-]*:\/\/\S*/gi, '')
    .replace(/\/\*.*?\*\//g, '')
    .replace(/(^|\s)\/\/.*$/, '')
    .replace(/^\s*\*.*$/, '')

const FORBIDDEN = [
  /(?<=[:'"`\[(,=]\s*)#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b/,
  new RegExp(`\\b(?:color|background|backgroundColor|borderColor|fill|stroke|outlineColor)\\s*[:=]\\s*\\{?\\s*['"\`]${NAMED}\\b`, 'i'),
  new RegExp(`\\b(?:bg|text|border|ring|fill|stroke|outline|shadow)-\\[${NAMED}\\]`),
  /\brgba?\(/,
  /\bhsla?\(/,
  /\boklch\(/,
  /\b(?:bg|text|border|ring|fill|stroke|from|to|via|outline|shadow|divide)-(?:white|black)\b/,
  /\b(?:bg|text|border|ring|fill|stroke|outline|shadow)-\[(?:#|rgb|hsl|oklch)/,
  /\b(?:bg|text|border|ring|fill|stroke|outline)-(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d/,
]

describe('colour guard', () => {
  it('noHardCodedColoursOutsideTheTokens', () => {
    const offences = Object.entries(FILES)
      .map(([path, text]) => [path.replace('/src/', ''), text] as const)
      .filter(([file]) => !ALLOWED.some((a) => file === a || file.startsWith(a)))
      .flatMap(([file, text]) =>
        text.split('\n').flatMap((line, i) => (FORBIDDEN.some((re) => re.test(code(line))) ? [`${file}:${i + 1}: ${line.trim()}`] : [])),
      )
    expect(Object.keys(FILES).length).toBeGreaterThan(50)
    expect(offences).toEqual([])
  })

  it('theGuardCatchesTheLiteralsItIsMeantTo', () => {
    const samples = ["fill: '#fff'", 'color: #0070F3', 'rgb(0 0 0)', 'rgba(0,0,0,.5)', 'bg-white', 'text-black', 'bg-[#fff]', 'text-red-500', "color: 'white'", "style={{ background: 'black' }}", 'fill="red"', 'bg-[red]', 'text-[white]']
    for (const s of samples) expect(FORBIDDEN.some((re) => re.test(code(s))), s).toBe(true)
    for (const s of ['bg-bg', 'text-mu', 'var(--s-blue)', 'border-bd2', 'bg-er/10', 'see issue #bad', 'https://x.y/#fff', '// #fff in a comment', "color: 'var(--tx)'", 'id="#main"', "foo#abc", "contrast: 'redundant'"])
      expect(FORBIDDEN.some((re) => re.test(code(s))), s).toBe(false)
  })
})
