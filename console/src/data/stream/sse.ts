/** One dispatched Server-Sent Event: its `event:` name (`message` when absent) and its joined `data:` lines. */
export interface SseEvent {
  event: string
  data: string
}

export interface SseDecoder {
  push(chunk: Uint8Array): void
}

/**
 * The text/event-stream grammar (WHATWG HTML, "Interpreting an event stream") over raw bytes:
 * UTF-8 decoded in streaming mode (a character split across chunks survives), lines ending in
 * CRLF, LF or a lone CR (a CRLF split across chunks counts once), `data:` lines joined with `\n`,
 * one leading space after the colon dropped, `id`, `retry` and unknown fields ignored, and an
 * event dispatched on the blank line only if it has data. `onComment` sees `: ping` heartbeats.
 */
export function createSseDecoder(onEvent: (e: SseEvent) => void, onComment?: () => void): SseDecoder {
  const text = new TextDecoder()
  let buffer = ''
  let event = ''
  let data: string[] = []
  let afterCr = false

  const line = (l: string) => {
    if (l === '') {
      if (data.length > 0) onEvent({ event: event || 'message', data: data.join('\n') })
      event = ''
      data = []
      return
    }
    if (l.startsWith(':')) return void onComment?.()
    const colon = l.indexOf(':')
    const field = colon < 0 ? l : l.slice(0, colon)
    const raw = colon < 0 ? '' : l.slice(colon + 1)
    const value = raw.startsWith(' ') ? raw.slice(1) : raw
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }

  return {
    push(chunk) {
      let start = 0
      buffer += text.decode(chunk, { stream: true })
      // The LF of a CRLF whose CR ended the previous chunk.
      if (afterCr && buffer !== '') {
        if (buffer.startsWith('\n')) start = 1
        afterCr = false
      }
      for (let i = start; i < buffer.length; i++) {
        const c = buffer[i]
        if (c !== '\n' && c !== '\r') continue
        line(buffer.slice(start, i))
        if (c === '\r' && i === buffer.length - 1) afterCr = true
        else if (c === '\r' && buffer[i + 1] === '\n') i++
        start = i + 1
      }
      buffer = buffer.slice(start)
    },
  }
}
