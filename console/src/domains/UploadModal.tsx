import { useState } from 'react'
import { Input } from '@/components/ui/input'
import Modal from './Modal'

// FileController.upload: an optional `path` part (one segment, as the backend validates it) and the `file` part.
const PATH = /^[A-Za-z0-9_-]+$/
const ACCEPT = 'image/jpeg,image/png,image/webp'

interface UploadModalProps {
  onSubmit(path: string, file: File): Promise<unknown>
  onClose(): void
}

/** Picks a folder and one image for `POST /api/v1/files/upload`; there is no JSON body for files. */
export default function UploadModal({ onSubmit, onClose }: UploadModalProps) {
  const [path, setPath] = useState('images')
  const [file, setFile] = useState<File | null>(null)
  const pathOk = PATH.test(path)

  return (
    <Modal
      title="Upload file"
      endpointLabel="POST /api/v1/files/upload"
      submitLabel="Upload"
      canSubmit={pathOk && file !== null}
      onSubmit={() => onSubmit(path, file as File)}
      onClose={onClose}
    >
      <label className="flex flex-col gap-1.5 font-medium">
        Path
        <Input name="path" value={path} aria-invalid={!pathOk} onChange={(e) => setPath(e.target.value)} className="font-mono" />
      </label>
      {pathOk ? (
        <p className="text-xs text-mu">Stored as path/generated-name. JPEG, PNG or WebP, up to 5 MB.</p>
      ) : (
        <p className="text-xs text-er">One folder name: letters, digits, _ or - only.</p>
      )}
      <label className="flex flex-col gap-1.5 font-medium">
        File
        <Input name="file" type="file" accept={ACCEPT} nativeInput onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </label>
    </Modal>
  )
}
