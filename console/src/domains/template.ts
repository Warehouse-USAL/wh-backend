import type { Endpoint } from './entities'

export interface Schema {
  $ref?: string
  type?: string | string[]
  format?: string
  nullable?: boolean
  enum?: unknown[]
  properties?: Record<string, Schema>
  additionalProperties?: unknown
  items?: Schema
  allOf?: Schema[]
  oneOf?: Schema[]
  anyOf?: Schema[]
}

interface Operation {
  tags?: string[]
  summary?: string
  security?: Record<string, string[]>[]
  requestBody?: { content?: Record<string, { schema?: Schema }> }
}

export interface OpenApiDoc {
  paths: Record<string, Record<string, Operation | undefined> | undefined>
  components: { schemas: Record<string, Schema> }
}

const REF = '#/components/schemas/'

/** An empty-valued JSON shaped like the named `/api-docs` schema. */
export function buildTemplate(schemaName: string, spec: OpenApiDoc): unknown {
  return build({ $ref: REF + schemaName }, spec, new Set())
}

/** The template for an endpoint's JSON request body, or `{}` when it takes none. */
export function requestTemplate(spec: OpenApiDoc, endpoint: Endpoint): unknown {
  const content = spec.paths[endpoint.path]?.[endpoint.method.toLowerCase()]?.requestBody?.content
  const schema = content?.['application/json']?.schema
  return schema ? build(schema, spec, new Set()) : {}
}

// `seen` holds the refs on the current branch, so a recursive schema ends in null instead of looping.
function build(schema: Schema, spec: OpenApiDoc, seen: Set<string>): unknown {
  if (schema.$ref) {
    const name = schema.$ref.slice(REF.length)
    const target = spec.components.schemas[name]
    if (!target || seen.has(name)) return null
    return build(target, spec, new Set(seen).add(name))
  }
  if (schema.allOf) {
    return Object.assign({}, ...schema.allOf.map((part) => build(part, spec, seen)))
  }
  const variants = schema.oneOf ?? schema.anyOf
  if (variants) {
    const first = variants.find((v) => typeOf(v) !== 'null') ?? variants[0]
    return build(first, spec, seen)
  }
  if (schema.enum) return schema.enum[0]
  switch (typeOf(schema)) {
    case 'object':
      return Object.fromEntries(
        Object.entries(schema.properties ?? {}).map(([key, value]) => [key, build(value, spec, seen)]),
      )
    case 'array':
      return schema.items ? [build(schema.items, spec, seen)] : []
    case 'string':
      return ''
    case 'integer':
    case 'number':
      return 0
    case 'boolean':
      return false
    default:
      return null
  }
}

// OpenAPI 3.1 writes nullable fields as `type: [T, "null"]`; the template uses T.
function typeOf(schema: Schema): string | undefined {
  const types = Array.isArray(schema.type) ? schema.type : [schema.type]
  const type = types.find((t) => t !== 'null') ?? types[0]
  return type ?? (schema.properties || schema.additionalProperties ? 'object' : undefined)
}

/** The fields of `doc` that the endpoint's request schema names; empty ones are left out (absent means unchanged). */
export function pickRequest(spec: OpenApiDoc, endpoint: Endpoint, doc: Record<string, unknown>): Record<string, unknown> {
  const template = requestTemplate(spec, endpoint)
  const keys = template !== null && typeof template === 'object' && !Array.isArray(template) ? Object.keys(template) : []
  return Object.fromEntries(keys.filter((k) => doc[k] !== undefined && doc[k] !== null).map((k) => [k, doc[k]]))
}

// springdoc's default path, then the one application.yml.example sets. The backend answers 401 for an
// unmapped route, so these go out without the session: a miss must never look like an expired token.
const SPEC_PATHS = ['/api/v3/api-docs', '/api/api-docs']

async function fetchSpec(): Promise<OpenApiDoc | null> {
  for (const path of SPEC_PATHS) {
    try {
      const response = await fetch(path, { headers: { Accept: 'application/json' } })
      const doc = response.ok ? ((await response.json()) as Partial<OpenApiDoc>) : null
      if (doc?.paths && doc.components) return doc as OpenApiDoc
    } catch {
      // Unreachable or not JSON: try the next path.
    }
  }
  return null
}

let specRequest: Promise<OpenApiDoc | null> | null = null

/** springdoc's document, fetched once per session; `null` (retried next time) when neither path serves it. */
export function loadSpec(): Promise<OpenApiDoc | null> {
  specRequest ??= fetchSpec().then((doc) => {
    if (!doc) specRequest = null
    return doc
  })
  return specRequest
}
