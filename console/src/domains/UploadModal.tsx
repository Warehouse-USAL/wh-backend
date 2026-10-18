import { useState } from 'react'
import { Input } from '@/components/ui/input'
import Modal from './Modal'

// FileController.upload takes one part, `file`, and stores it under this path with a generated name.
const UPLOAD_PATH = 'images'
const ACCEPT = 'image/jpeg,image/png,image/webp'

/** Picks one image for `POST /api/v1/files/upload`; there is no JSON body for files. */
export default function UploadModal({ onSubmit, onClose }: { onSubmit(file: File): Promise<void>; onClose(): void }) {
  const [file, setFile] = useState<File | null>(null)

  return (
    <Modal
      title="Upload file"
      endpointLabel="POST /api/v1/files/upload"
      submitLabel="Upload"
      canSubmit={file !== null}
      onSubmit={() => onSubmit(file as File)}
      onClose={onClose}
    >
      <label className="flex flex-col gap-1.5 font-medium">
        Path
        <Input name="path" value={UPLOAD_PATH} readOnly className="font-mono" />
      </label>
      <p className="text-xs text-mu">The upload endpoint stores every file under images/ with a generated name. JPEG, PNG or WebP, up to 5 MB.</p>
      <label className="flex flex-col gap-1.5 font-medium">
        File
        <Input name="file" type="file" accept={ACCEPT} nativeInput onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </label>
    </Modal>
  )
}
