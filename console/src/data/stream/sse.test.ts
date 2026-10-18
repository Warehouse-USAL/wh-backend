import { describe, expect, it } from 'vitest'
import { type SseEvent, createSseDecoder } from './sse'

const bytes = (s: string) => new TextEncoder().encode(s)

/** Feeds `chunks` (strings or raw bytes) and returns the events and comment count. */
function decode(...chunks: (string | Uint8Array)[]) {
  const events: SseEvent[] = []
  const decoder = createSseDecoder((e) => events.push(e))
  for (const c of chunks) decoder.push(typeof c === 'string' ? bytes(c) : c)
  return events
}

describe('createSseDecoder', () => {
  it('parsesNamedEventsAndDefaultsToMessage', () => {
    expect(decode('event: snapshot\ndata: {"id":"a"}\n\ndata: x\n\n')).toEqual([
      { event: 'snapshot', data: '{"id":"a"}' },
      { event: 'message', data: 'x' },
    ])
  })

  it('reassemblesAnEventSplitAnywhereAcrossChunks', () => {
    const frame = 'event: update\ndata: {"id":"p95","mode":"replace"}\n\n'
    for (let cut = 1; cut < frame.length; cut++) {
      expect(decode(frame.slice(0, cut), frame.slice(cut))).toEqual([{ event: 'update', data: '{"id":"p95","mode":"replace"}' }])
    }
    expect(decode(...frame.split(''))).toEqual([{ event: 'update', data: '{"id":"p95","mode":"replace"}' }])
  })

  it('acceptsCrlfLfAndLoneCrLineEndsEvenWhenACrlfIsSplit', () => {
    expect(decode('event: a\r\ndata: 1\r\n\r\n')).toEqual([{ event: 'a', data: '1' }])
    expect(decode('event: b\rdata: 2\r\r')).toEqual([{ event: 'b', data: '2' }])
    // The \r ends one chunk and its \n starts the next: one line end, not two (no early dispatch).
    expect(decode('event: c\r', '\ndata: 3\r', '\n\r', '\n')).toEqual([{ event: 'c', data: '3' }])
    // Even with an empty read in between.
    expect(decode('data: 1\r', new Uint8Array(), '\ndata: 2\n\n')).toEqual([{ event: 'message', data: '1\n2' }])
  })

  it('joinsMultiLineDataWithNewlines', () => {
    expect(decode('data: first\ndata:second\ndata:  third\n\n')).toEqual([{ event: 'message', data: 'first\nsecond\n third' }])
  })

  it('ignoresCommentsHeartbeatsUnknownFieldsAndEventsWithoutData', () => {
    expect(decode(': ping\n\n', 'id: 7\nretry: 10\nevent: lonely\n\n', 'nonsense\nevent: x\ndata\n\n')).toEqual([{ event: 'x', data: '' }])
  })

  it('reportsActivityForCommentsSoAWatchdogSeesHeartbeats', () => {
    let comments = 0
    const decoder = createSseDecoder(() => undefined, () => comments++)
    decoder.push(bytes(': ping\n\n: ping\n'))
    expect(comments).toBe(2)
  })

  it('keepsUtf8CharactersSplitAcrossChunks', () => {
    const all = bytes('data: {"message":"La suscripción no es válida."}\n\n')
    const at = all.indexOf(0xc3) + 1 // inside the two bytes of "ó"
    expect(decode(all.slice(0, at), all.slice(at))).toEqual([{ event: 'message', data: '{"message":"La suscripción no es válida."}' }])
  })

  it('waitsForTheBlankLineBeforeDispatching', () => {
    expect(decode('event: snapshot\ndata: {}\n')).toEqual([])
  })
})
