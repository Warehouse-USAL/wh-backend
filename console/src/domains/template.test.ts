import { describe, expect, it } from 'vitest'
import { mockFetch, jsonResponse } from '@/test/http'
import spec from './__fixtures__/api-docs.json'
import { buildTemplate, loadSpec, type OpenApiDoc, requestTemplate } from './template'

const doc = (schemas: OpenApiDoc['components']['schemas'], paths: OpenApiDoc['paths'] = {}): OpenApiDoc => ({
  paths,
  components: { schemas },
})

describe('buildTemplate', () => {
  it('buildTemplateEmptiesEachType', () => {
    const spec = doc({
      Item: { type: 'object', properties: { product_id: { type: 'string' }, quantity: { type: 'integer' } } },
      All: {
        type: 'object',
        properties: {
          text: { type: 'string' },
          when: { type: 'string', format: 'date-time' },
          count: { type: 'integer', format: 'int32' },
          ratio: { type: 'number' },
          flag: { type: 'boolean' },
          priority: { type: 'string', enum: ['low', 'high'] },
          items: { type: 'array', items: { $ref: '#/components/schemas/Item' } },
          nested: { type: 'object', properties: { x: { type: 'number' } } },
          maybe: { type: ['string', 'null'] },
          gone: { type: 'null' },
          legacy: { type: 'string', nullable: true },
          map: { type: 'object', additionalProperties: { type: 'string' } },
          untyped: {},
        },
      },
    })

    expect(buildTemplate('All', spec)).toEqual({
      text: '',
      when: '',
      count: 0,
      ratio: 0,
      flag: false,
      priority: 'low',
      items: [{ product_id: '', quantity: 0 }],
      nested: { x: 0 },
      maybe: '',
      gone: null,
      legacy: '',
      map: {},
      untyped: null,
    })
  })

  it('mergesAllOfAndTakesTheFirstOneOfOrAnyOf', () => {
    const spec = doc({
      Base: { type: 'object', properties: { id: { type: 'string' } } },
      Mixed: {
        allOf: [{ $ref: '#/components/schemas/Base' }, { properties: { size: { type: 'integer' } } }],
      },
      Choice: { oneOf: [{ type: 'null' }, { type: 'boolean' }] },
      Any: { anyOf: [{ type: 'number' }, { type: 'string' }] },
    })

    expect(buildTemplate('Mixed', spec)).toEqual({ id: '', size: 0 })
    expect(buildTemplate('Choice', spec)).toBe(false)
    expect(buildTemplate('Any', spec)).toBe(0)
  })

  it('stopsAtRecursiveAndMissingReferences', () => {
    const spec = doc({
      Node: { type: 'object', properties: { name: { type: 'string' }, child: { $ref: '#/components/schemas/Node' } } },
    })

    expect(buildTemplate('Node', spec)).toEqual({ name: '', child: null })
    expect(buildTemplate('Missing', spec)).toBeNull()
  })

  it('buildsTheRecordedCreateProductRequest', () => {
    const template = buildTemplate('CreateProductRequest', spec as OpenApiDoc) as Record<string, unknown>

    expect(template).toMatchObject({ sku: '', name: '', category: '', height: 0, max_quantity_per_order: 0 })
    expect(template.images).toEqual([{ url: '', alt: '', is_primary: false }])
  })
})

describe('requestTemplate', () => {
  it('readsTheJsonRequestSchemaOfTheEndpoint', () => {
    const recorded = spec as OpenApiDoc

    expect(requestTemplate(recorded, { method: 'POST', path: '/warehouse/lines/{lineId}/positions' })).toEqual({
      position_name: '',
      maximum_capacity: 0,
      size_stock_to_save: 'CAJA',
      qr_code: '',
    })
    expect(requestTemplate(recorded, { method: 'POST', path: '/api/v1/files/upload' })).toEqual({ file: '' })
  })

  it('isAnEmptyObjectWhenTheEndpointHasNoBody', () => {
    const recorded = spec as OpenApiDoc

    expect(requestTemplate(recorded, { method: 'DELETE', path: '/products/{id}' })).toEqual({})
    expect(requestTemplate(recorded, { method: 'POST', path: '/nowhere' })).toEqual({})
  })
})

describe('loadSpec', () => {
  it('fetchesTheDocumentOnceAndRetriesAfterAFailure', async () => {
    const fetchMock = mockFetch(jsonResponse(503, { error: { code: 'DOWN', message: 'no docs' } }), jsonResponse(200, spec))

    await expect(loadSpec()).rejects.toMatchObject({ code: 'DOWN' })
    await expect(loadSpec()).resolves.toMatchObject({ openapi: '3.1.0' })
    await expect(loadSpec()).resolves.toMatchObject({ openapi: '3.1.0' })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/v3/api-docs')
  })
})
