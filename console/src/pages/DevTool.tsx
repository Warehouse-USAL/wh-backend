import { useStatus } from '@/data/streamHooks'
import Header from '@/layout/Header'
import { Skeleton } from './Overview'

type Props = {
  name: string
  /**
   * The console nginx's frame-safe proxy for this tool (8091 mongo-express, 8092 redpanda-console).
   * http only, like the dev profile: the CSP's `frame-src http://*:8091 http://*:8092` would block
   * these frames under an https console until that CSP changes (see Task 29).
   */
  port: number
}

// The tools need scripts, their own cookies and storage (same-origin is their origin, not the
// console's), forms and downloads; never top navigation, so they cannot take over the console.
const SANDBOX = 'allow-scripts allow-same-origin allow-forms allow-popups allow-downloads'

/** A dev tool embedded through the console's proxy port on this host; exists only under the dev profile. */
export default function DevTool({ name, port }: Props) {
  const { data, loading } = useStatus()
  const url = `${location.protocol}//${location.hostname}:${port}/`
  const open = (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex min-h-8 items-center rounded-md border border-bd2 bg-bg px-3 text-[13px] hover:bg-bg2"
    >
      Open in new tab
    </a>
  )
  return (
    <>
      <Header crumb="Dev tools" title={name} tabs={['Embedded']} actions={data.dev && open} timeControls={false} />
      <div className="flex flex-1 flex-col px-6 pt-5 pb-8">
        {loading ? (
          <Skeleton className="flex-1" />
        ) : data.dev ? (
          <iframe
            src={url}
            title={name}
            sandbox={SANDBOX}
            referrerPolicy="no-referrer"
            className="min-h-[420px] w-full flex-1 rounded-lg border border-bd bg-bg"
          />
        ) : (
          <p className="text-[13px] text-mu">
            {name} runs only under the dev compose profile, and this backend does not report it.
          </p>
        )}
      </div>
    </>
  )
}
