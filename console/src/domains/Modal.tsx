import { type ReactNode, useState } from 'react'
import { errorText } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Dialog, DialogPopup, DialogTitle } from '@/components/ui/dialog'

interface ModalProps {
  title: string
  /** A pill next to the title, e.g. `JSON`. */
  tag?: string
  /** Shown in the footer, e.g. `POST /orders`. */
  endpointLabel: string
  submitLabel: string
  destructive?: boolean
  canSubmit?: boolean
  /** Throw to keep the modal open with the error under the body. */
  onSubmit(): Promise<void>
  onClose(): void
  children: ReactNode
}

/** The frame every Domains write uses: title, body, inline error, endpoint, Cancel and submit. */
export default function Modal({ title, tag, endpointLabel, submitLabel, destructive, canSubmit = true, onSubmit, onClose, children }: ModalProps) {
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  async function submit() {
    setPending(true)
    setError(null)
    try {
      await onSubmit()
    } catch (e) {
      setError(errorText(e))
      setPending(false)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogPopup className="max-w-[600px] rounded-xl" bottomStickOnMobile={false}>
        <div className="flex items-center gap-2.5 border-b border-bd px-4 py-3.5 pr-12">
          <DialogTitle className="font-mono text-[13px] font-medium">{title}</DialogTitle>
          {tag && <span className="rounded-full border border-bd bg-bg2 px-2 font-mono text-[11px] text-mu">{tag}</span>}
        </div>
        <div className="flex flex-col gap-2.5 px-4 py-3.5">
          {children}
          {error && (
            <p role="alert" className="text-er whitespace-pre-wrap">
              {error}
            </p>
          )}
        </div>
        <div className="flex items-center gap-2 border-t border-bd px-4 py-3">
          <span className="font-mono text-mu">{endpointLabel}</span>
          <span className="flex-1" />
          <Button variant="outline" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button variant={destructive ? 'destructive' : 'default'} size="sm" loading={pending} disabled={!canSubmit} onClick={submit}>
            {submitLabel}
          </Button>
        </div>
      </DialogPopup>
    </Dialog>
  )
}
