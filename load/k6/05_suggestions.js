// Suggestions service (Go + Redis): reads (cart / user / hot) and a burst of order.completed ingests,
// produced by completing real orders through the Infra endpoint (backend -> Kafka -> suggestions).
import http from 'k6/http';
import { check } from 'k6';
import { BACKEND, SUGGESTIONS, DEMO_PASSWORD, auth, login, warehouseToken, stockedProduct, orderBody, rnd } from './lib.js';

export const options = {
  scenarios: {
    reads: { executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s', duration: '30s', preAllocatedVUs: 50, maxVUs: 200, exec: 'reads' },
    ingest: { executor: 'shared-iterations', vus: 10, iterations: Number(__ENV.INGEST_ORDERS || 300), maxDuration: '120s', exec: 'ingest', startTime: '0s' },
  },
  thresholds: {
    'http_req_duration{scenario:reads}': ['p(95)<100', 'p(99)<250'],
    'http_req_failed{scenario:reads}': ['rate<0.01'],
  },
};

export function setup() {
  const wh = warehouseToken();
  const products = [];
  for (let i = 0; i < 5; i++) products.push(stockedProduct(wh, 1500));
  const session = login('operator1@smartwarehouse.local', DEMO_PASSWORD).json();
  const op = session.token;
  const skus = products.map((id) => http.get(`${BACKEND}/products/${id}`, auth(wh)).json('product.sku'));
  return { op, wh, products, skus, userId: session.user.id };
}

export function reads(d) {
  const a = auth(d.op);
  const roll = Math.random();
  let res;
  if (roll < 0.5) res = http.get(`${SUGGESTIONS}/v1/recommendations/cart?skus=${d.skus[rnd(5)]},${d.skus[rnd(5)]}&limit=10`, { ...a, tags: { name: 'GET cart' } });
  else if (roll < 0.8) res = http.get(`${SUGGESTIONS}/v1/recommendations/hot?limit=10`, { ...a, tags: { name: 'GET hot' } });
  else res = http.get(`${SUGGESTIONS}/v1/recommendations/user/${d.userId}?limit=10`, { ...a, tags: { name: 'GET user' } });
  check(res, { '2xx': (r) => r.status >= 200 && r.status < 300 });
}

export function ingest(d) {
  // create + complete: the backend publishes order.completed on completion
  const created = http.post(`${BACKEND}/orders`, orderBody(d.products[rnd(5)], 1), { ...auth(d.op), tags: { name: 'ingest POST /orders' } });
  if (created.status !== 201) return;
  const id = created.json('order.id');
  const hdr = { headers: { 'Content-Type': 'application/json', 'X-Api-Key': __ENV.INFRA_API_KEY } };
  http.post(`${BACKEND}/infra/orders/${id}/status`, JSON.stringify({ status: 'in_progress', vehicle_id: 'VHC-LOAD', timestamp: new Date().toISOString() }), hdr);
  const done = http.post(`${BACKEND}/infra/orders/${id}/status`, JSON.stringify({ status: 'completed', vehicle_id: 'VHC-LOAD', timestamp: new Date().toISOString() }), { ...hdr, tags: { name: 'ingest completed' } });
  check(done, { 'completed 2xx': (r) => r.status >= 200 && r.status < 300 });
}
