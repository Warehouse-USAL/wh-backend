import { useEffect, useRef, useState } from 'react'
import { api, apiBlob, errorText } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import Header from '@/layout/Header'
import { type Endpoint, type EntityConfig, type Extra, type Scope, type ScopeOption, fieldOf, fillPath } from './entities'
import JsonModal from './JsonModal'
import Modal from './Modal'
import { Input } from '@/components/ui/input'
import { loadSpec, pickRequest, requestTemplate } from './template'
import UploadModal from './UploadModal'

const PAGE_SIZE = 25
const SEARCH_DELAY_MS = 300
const NO_SCHEMA = 'Schema unavailable, template empty.'

type Row = Record<string, unknown>
type Vars = Record<string, unknown>
interface Pagination {
  page: number
  total_elements: number
  total_pages: number
}
interface Loaded {
  query: string
  reload: number
  rows?: Row[]
  pagination?: Pagination
  error?: string
}
type Dialog =
  | {
      kind: 'json'
      title: string
      endpoint: Endpoint
      initial: unknown
      vars: Vars
      submitLabel: string
      hint: string
      inspect?: boolean
    }
  | { kind: 'upload' }
  | { kind: 'delete'; row: Row }

/** A Domains page: one table per entity, switched by tabs when the page has several. */
export default function EntityPage({ title, entities }: { title: string; entities: EntityConfig[] }) {
  const [key, setKey] = useState(entities[0].key)
  const entity = entities.find((e) => e.key === key) ?? entities[0]
  const tabs = entities.length > 1 ? entities.map((e) => e.title) : undefined
  const onTab = (tab: string) => setKey(entities.find((e) => e.title === tab)?.key ?? key)
  return <EntityTable key={entity.key} entity={entity} title={title} tabs={tabs} onTab={onTab} />
}

interface TableProps {
  entity: EntityConfig
  title: string
  tabs?: string[]
  onTab(tab: string): void
}

function EntityTable({ entity, title, tabs, onTab }: TableProps) {
  const scope = useScope(entity.scope)
  const [page, setPage] = useState(0)
  const [reload, setReload] = useState(0)
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [term, setTerm] = useState('')
  const [search, setSearch] = useState('')
  // Ticket of the latest dialog request: a slower earlier one (Edit, New, an extra) must not open over a later one.
  const opening = useRef(0)
  const [inactive, setInactive] = useState(false)

  // Typing settles before the list refetches, and a new search starts at the first page.
  useEffect(() => {
    const id = setTimeout(() => {
      setSearch(term.trim())
      setPage(0)
    }, SEARCH_DELAY_MS)
    return () => clearTimeout(id)
  }, [term])

  const scopeVars: Vars = entity.scope ? { [entity.scope.param]: scope.value } : {}
  const filter = entity.search && search ? `&${entity.search}=${encodeURIComponent(search)}` : ''
  const only = entity.inactive && inactive ? `&${entity.inactive}=false` : ''
  const query = scope.ready ? `${fillPath(entity.listPath, scopeVars)}?page=${page}&size=${PAGE_SIZE}${filter}${only}` : null

  useEffect(() => {
    if (!query) return
    let active = true
    api(query).then(
      (body) => {
        const { [entity.listKey]: rows, pagination } = body as Record<string, unknown>
        if (active) setLoaded({ query, reload, rows: rows as Row[], pagination: pagination as Pagination | undefined })
      },
      (e: unknown) => active && setLoaded({ query, reload, error: errorText(e) }),
    )
    return () => {
      active = false
    }
  }, [query, reload, entity.listKey])

  const current = loaded?.query === query && loaded.reload === reload ? loaded : null
  const rowVars = (row: Row): Vars => ({ ...scopeVars, ...row, id: row[entity.idField] })
  const idOf = (row: Row) => String(row[entity.idField])
  const done = () => {
    setDialog(null)
    setReload((r) => r + 1)
  }

  async function act(work: () => Promise<void>) {
    setActionError(null)
    try {
      await work()
    } catch (e) {
      setActionError(errorText(e))
    }
  }

  const show = (next: Dialog | null) => {
    opening.current++
    setDialog(next)
  }

  const openNew = (endpoint: Endpoint) =>
    act(async () => {
      const ticket = ++opening.current
      const spec = await loadSpec()
      if (ticket !== opening.current) return
      const initial = spec ? requestTemplate(spec, endpoint) : {}
      const hint = spec ? 'Prefilled with the structure of the request. Fill in the values and press Create.' : NO_SCHEMA
      setDialog({ kind: 'json', title: `New ${entity.noun}`, endpoint, initial, vars: scopeVars, submitLabel: 'Create', hint })
    })

  const openExtra = (extra: Extra, row: Row) =>
    act(async () => {
      const ticket = ++opening.current
      const spec = await loadSpec()
      if (ticket !== opening.current) return
      const prefill = extra.prefill?.(row) ?? {}
      const initial = spec ? { ...(requestTemplate(spec, extra.endpoint) as Row), ...pickRequest(spec, extra.endpoint, prefill) } : {}
      const hint = spec ? 'Prefilled with the structure of the request.' : NO_SCHEMA
      setDialog({
        kind: 'json',
        title: `${extra.label}: ${idOf(row)}`,
        endpoint: extra.endpoint,
        initial,
        vars: rowVars(row),
        submitLabel: extra.inspect ? 'Run' : 'Send',
        hint,
        inspect: extra.inspect,
      })
    })

  // Only the latest click opens, however the fetches race.
  const openEdit = (endpoint: Endpoint, row: Row) =>
    act(async () => {
      const ticket = ++opening.current
      const { get } = entity
      const body = get ? ((await api(fillPath(get.path, rowVars(row)))) as Row) : row
      const doc = (get?.key ? body[get.key] : body) as Row
      const spec = await loadSpec()
      if (ticket !== opening.current) return
      const initial = spec ? pickRequest(spec, endpoint, entity.toRequest?.(doc) ?? doc) : doc
      const hint = spec
        ? 'Only fields this endpoint accepts are shown; unknown fields are ignored by the API.'
        : 'Schema unavailable, showing the whole document.'
      setDialog({ kind: 'json', title: `${entity.listKey} / ${idOf(row)}`, endpoint, initial, vars: rowVars(row), submitLabel: 'Save', hint })
    })

  const download = (path: string, row: Row) =>
    act(async () => {
      const url = URL.createObjectURL(await apiBlob(fillPath(path, rowVars(row))))
      const link = document.createElement('a')
      link.href = url
      link.download = idOf(row)
      link.click()
      // Revoking in the same task can cancel the download in some browsers.
      setTimeout(() => URL.revokeObjectURL(url), 0)
    })

  const write = async (endpoint: Endpoint, vars: Vars, body?: BodyInit) => {
    await api(fillPath(endpoint.path, vars), { method: endpoint.method, body })
    done()
  }

  const { create, remove } = entity
  const newButton = create && scope.ready && (
    <Button
      size="sm"
      onClick={() => (entity.upload ? show({ kind: 'upload' }) : openNew(create))}
    >
      New {entity.noun}
    </Button>
  )
  const rowActions = (row: Row) => {
    const id = idOf(row)
    const { edit, download: file } = entity
    if (entity.actionable?.(row) === false) return null
    return (
      <>
        {edit && <RowAction label="Edit" id={id} onClick={() => openEdit(edit, row)} />}
        {entity.extras.map((x) => (
          <RowAction key={x.label} label={x.label} id={id} onClick={() => openExtra(x, row)} />
        ))}
        {file && <RowAction label="Download" id={id} onClick={() => download(file, row)} />}
        {remove && <RowAction label="Delete" id={id} danger onClick={() => show({ kind: 'delete', row })} />}
      </>
    )
  }

  const noun = entity.title.toLowerCase()
  function tableNote(): Note {
    if (scope.error) return { error: scope.error }
    if (entity.scope && scope.options?.length === 0) return { empty: `Create a ${entity.scope.label.toLowerCase()} first.` }
    if (!current) return 'loading'
    if (current.error) return { error: current.error }
    if (current.rows?.length !== 0) return null
    if (entity.inactive) return { empty: inactive ? `No inactive ${noun}.` : `No ${noun} yet. Inactive ones are hidden: tick "Inactive only" to list them.` }
    return { empty: `No ${noun} yet.` }
  }

  const pagination = current?.pagination
  const count = pagination?.total_elements ?? current?.rows?.length

  return (
    <>
      <Header crumb="Domains" title={title} tabs={tabs} tab={entity.title} onTab={onTab} chips={[entity.source]} actions={newButton} timeControls={false} />
      <div className="flex flex-col gap-4 px-6 pt-5 pb-8">
        {actionError && (
          <p role="alert" className="text-er">
            {actionError}
          </p>
        )}
        <section aria-label={entity.title} className="flex min-w-0 flex-col rounded-lg border border-bd bg-bg">
          <div className="flex flex-wrap items-center gap-3 px-3 pt-2.5 pb-1">
            {entity.scope && scope.options && scope.options.length > 0 && (
              <ScopePicker
                scope={entity.scope}
                options={scope.options}
                value={String(scope.value)}
                onChange={(value) => {
                  scope.choose(value)
                  setPage(0)
                }}
              />
            )}
            {entity.search && (
              <Input
                type="search"
                name="search"
                aria-label="Search"
                placeholder="Search"
                value={term}
                onChange={(e) => setTerm(e.target.value)}
                className="max-w-[260px]"
              />
            )}
            {entity.inactive && (
              <label className="flex items-center gap-1.5 text-xs text-mu">
                <input
                  type="checkbox"
                  checked={inactive}
                  onChange={(e) => {
                    setInactive(e.target.checked)
                    setPage(0)
                  }}
                />
                Inactive only
              </label>
            )}
            <span className="flex-1" />
            {count !== undefined && <span className="text-xs text-mu">{`${count} ${count === 1 ? entity.noun : noun}`}</span>}
            <Button variant="outline" size="sm" onClick={() => setReload((r) => r + 1)}>
              Refresh
            </Button>
          </div>
          <Table aria-label={entity.title}>
            <TableHeader>
              <TableRow>
                {entity.columns.map((c) => (
                  <TableHead key={c.field} className="h-auto px-3 py-2 text-xs">
                    {c.header}
                  </TableHead>
                ))}
                <TableHead className="h-auto px-3 py-2 text-right text-xs">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {current?.rows?.map((row) => (
                <TableRow key={idOf(row)}>
                  {entity.columns.map((c) => (
                    <TableCell key={c.field} className={c.field === entity.idField ? 'px-3 py-2 font-mono text-xs' : 'px-3 py-2'}>
                      <Cell value={fieldOf(row, c.field)} badge={c.badge} format={c.format} />
                    </TableCell>
                  ))}
                  <TableCell className="px-3 py-1 text-right">{rowActions(row)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <TableState note={tableNote()} />
          {pagination && (
            <div className="flex items-center justify-end gap-2 border-t border-bd px-3 py-2 text-xs text-mu">
              <span>{`Page ${pagination.page + 1} of ${Math.max(pagination.total_pages, 1)}`}</span>
              <Button variant="outline" size="xs" aria-label="Previous page" disabled={page === 0} onClick={() => setPage(page - 1)}>
                Previous
              </Button>
              <Button
                variant="outline"
                size="xs"
                aria-label="Next page"
                disabled={pagination.page + 1 >= pagination.total_pages}
                onClick={() => setPage(page + 1)}
              >
                Next
              </Button>
            </div>
          )}
        </section>
      </div>
      {dialog?.kind === 'json' && (
        <JsonModal
          title={dialog.title}
          initial={dialog.initial}
          endpointLabel={`${dialog.endpoint.method} ${dialog.endpoint.path}`}
          submitLabel={dialog.submitLabel}
          hint={dialog.hint}
          inspect={dialog.inspect}
          onSubmit={(body) => {
            const sent = JSON.stringify(body)
            if (dialog.inspect) return api(fillPath(dialog.endpoint.path, dialog.vars), { method: dialog.endpoint.method, body: sent })
            return write(dialog.endpoint, dialog.vars, sent)
          }}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'upload' && create && (
        <UploadModal
          onSubmit={(path, file) => {
            const form = new FormData()
            form.append('path', path)
            form.append('file', file)
            return write(create, {}, form)
          }}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'delete' && remove && (
        <Modal
          title={`Delete ${entity.noun} ${idOf(dialog.row)}?`}
          endpointLabel={`DELETE ${remove.path}`}
          submitLabel="Delete"
          destructive
          onSubmit={() => write(remove, rowVars(dialog.row))}
          onClose={() => setDialog(null)}
        >
          <p className="text-mu">This cannot be undone.</p>
        </Modal>
      )}
    </>
  )
}

/** Options for the entity's parent; `ready` once a parent is chosen (or none is needed). */
function useScope(scope: Scope | undefined) {
  const [loaded, setLoaded] = useState<{ options?: ScopeOption[]; error?: string }>({})
  const [chosen, setChosen] = useState<string | null>(null)
  useEffect(() => {
    if (!scope) return
    let active = true
    scope.options().then(
      (options) => active && setLoaded({ options }),
      (e: unknown) => active && setLoaded({ error: errorText(e) }),
    )
    return () => {
      active = false
    }
  }, [scope])
  const value = chosen ?? loaded.options?.[0]?.value ?? null
  return { ...loaded, value, ready: !scope || value !== null, choose: setChosen }
}

function ScopePicker({ scope, options, value, onChange }: { scope: Scope; options: ScopeOption[]; value: string; onChange(v: string): void }) {
  return (
    <label className="flex items-center gap-2 text-xs text-mu">
      {scope.label}
      <select
        name={scope.param}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="min-h-8 rounded-md border border-bd2 bg-bg px-2.5 text-[13px] text-tx"
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

function RowAction({ label, id, danger, onClick }: { label: string; id: string; danger?: boolean; onClick(): void }) {
  return (
    <button
      type="button"
      aria-label={`${label} ${id}`}
      onClick={onClick}
      className={`cursor-pointer px-1.5 py-0.5 ${danger ? 'text-er' : 'text-blue'} hover:underline`}
    >
      {label}
    </button>
  )
}

function Cell({ value, badge, format }: { value: unknown; badge?: boolean; format?(value: unknown): string }) {
  if (value === null || value === undefined) return <span className="text-mu">—</span>
  const text = format ? format(value) : typeof value === 'object' ? JSON.stringify(value) : typeof value === 'boolean' ? (value ? 'yes' : 'no') : String(value)
  if (!badge) return text
  return <span className="rounded-full border border-bd bg-bg2 px-2 font-mono text-[11px] text-mu">{text}</span>
}

type Note = 'loading' | { error: string } | { empty: string } | null

/** Below the rows: loading, empty or the API's error; never a made-up zero. */
function TableState({ note }: { note: Note }) {
  if (note === null) return null
  if (note === 'loading') {
    return (
      <p role="status" className="px-3 py-6 text-center text-mu">
        Loading…
      </p>
    )
  }
  if ('error' in note) {
    return (
      <p role="alert" className="px-3 py-6 text-center text-er">
        {note.error}
      </p>
    )
  }
  return <p className="px-3 py-6 text-center text-mu">{note.empty}</p>
}
