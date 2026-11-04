// Product / order listing and POST /query/orders over the seeded history (~25k orders), with realistic
// pagination: mostly the first pages, sometimes deep ones, plus status and date filters.
import http from 'k6/http';
import { check } from 'k6';
import { BACKEND, auth, warehouseToken, rnd } from './lib.js';

export const options = {
  scenarios: {
    listing: { executor: 'constant-vus', vus: Number(__ENV.VUS || 30), duration: __ENV.DURATION || '45s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:GET /products}': ['p(95)<500'],
    'http_req_duration{name:GET /orders}': ['p(95)<800'],
    'http_req_duration{name:GET /orders deep}': ['p(95)<2000'],
    'http_req_duration{name:POST /query/orders}': ['p(95)<1000'],
  },
};

export function setup() {
  const t = warehouseToken();
  const total = http.get(`${BACKEND}/orders?size=1`, auth(t)).json('pagination.total_elements');
  return { t, pages: Math.max(1, Math.floor(total / 20)) };
}

export default function (d) {
  const a = auth(d.t);
  const roll = Math.random();
  let res;
  if (roll < 0.2) {
    res = http.get(`${BACKEND}/products?page=${rnd(5)}&size=20`, { ...a, tags: { name: 'GET /products' } });
  } else if (roll < 0.35) {
    res = http.get(`${BACKEND}/products?search=${['lap', 'mou', 'key', 'phone'][rnd(4)]}&size=20`, { ...a, tags: { name: 'GET /products search' } });
  } else if (roll < 0.6) {
    res = http.get(`${BACKEND}/orders?page=${rnd(5)}&size=20`, { ...a, tags: { name: 'GET /orders' } });
  } else if (roll < 0.7) {
    res = http.get(`${BACKEND}/orders?page=${rnd(d.pages)}&size=20`, { ...a, tags: { name: 'GET /orders deep' } });
  } else if (roll < 0.8) {
    const st = ['completed', 'cancelled', 'pending'][rnd(3)];
    res = http.get(`${BACKEND}/orders?status=${st}&size=20&page=${rnd(10)}`, { ...a, tags: { name: 'GET /orders?status' } });
  } else {
    const body = { filters: [{ field: 'status', op: 'eq', value: ['COMPLETED', 'CANCELLED'][rnd(2)] }], sort: [{ field: 'created_at', dir: 'desc' }], size: 20, page: rnd(20) };
    res = http.post(`${BACKEND}/query/orders`, JSON.stringify(body), { ...a, tags: { name: 'POST /query/orders' } });
  }
  check(res, { '2xx': (r) => r.status >= 200 && r.status < 300 });
}
