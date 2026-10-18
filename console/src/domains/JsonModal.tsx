import { useState } from 'react'
import Modal from './Modal'

interface JsonModalProps {
  title: string
  initial: unknown
  endpointLabel: string
  onSubmit(body: unknown): Promise<void>
  onClose(): void
}

/** A bare JSON editor: the body is parsed locally and sent as typed; the API's error shows inline. */
export default function JsonModal({ title, initial, endpointLabel, onSubmit, onClose }: JsonModalProps) {
  const [text, setText] = useState(() => JSON.stringify(initial, null, 2))
  const lines = text.split('\n').length

  return (
    <Modal title={title} tag="JSON" endpointLabel={endpointLabel} submitLabel="Save" onSubmit={() => onSubmit(parse(text))} onClose={onClose}>
      {/* The textarea grows to fit, so the gutter and the text scroll together. */}
      <div className="flex max-h-[55vh] min-h-[260px] overflow-auto rounded-md border border-bd bg-bg2 font-mono text-xs leading-[1.6]">
        <div data-testid="gutter" aria-hidden className="border-r border-bd py-2.5 pr-2 pl-3 text-right text-mu select-none">
          {Array.from({ length: lines }, (_, i) => (
            <div key={i}>{i + 1}</div>
          ))}
        </div>
        <textarea
          aria-label="JSON body"
          name="body"
          value={text}
          rows={lines}
          wrap="off"
          spellCheck={false}
          onChange={(e) => setText(e.target.value)}
          className="flex-1 resize-none overflow-y-hidden bg-transparent px-3 py-2.5 outline-none"
        />
      </div>
    </Modal>
  )
}

function parse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch (e) {
    throw new Error(`Invalid JSON: ${describe(text, (e as SyntaxError).message)}`, { cause: e })
  }
}

// V8 echoes the whole text after an unexpected token, and older engines give a position without a line.
function describe(text: string, message: string): string {
  const short = message.replace(/, ".*" is not valid JSON$/s, '')
  const position = /position (\d+)/.exec(short)
  if (!position || /line \d+/.test(short)) return short
  const before = text.slice(0, Number(position[1])).split('\n')
  return `${short} (line ${before.length}, column ${before[before.length - 1].length + 1})`
}
