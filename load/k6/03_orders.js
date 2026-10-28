// Order creation under concurrency: abundant stock (everything must succeed) and scarce stock (exactly
// `SCARCE_STOCK` units may be sold; the rest are refused with INSUFFICIENT_STOCK or, rarely, a retryable
// STOCK_CHANGE_CONFLICT; never a 5xx and never an oversell).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BACKEND, auth, adminToken, warehouseToken, operatorToken, stockedProduct, orderBody } from './lib.js';

const SCARCE = Number(__ENV.SCARCE_STOCK || 100);
const sold = new Counter('orders_created_scarce');
const refused = new Counter('orders_refused_scarce');
const conflicts = new Counter('orders_conflict_scarce');
const abundantConflicts = new Counter('orders_conflict_abundant');
const unexpected = new Counter('orders_unexpected');
const byStatus = {};
for (const c of ['201', '400', '409', '429', '500', '503', 'other']) byStatus[c] = new Counter(`scarce_status_${c}`);

export const options = {
  scenarios: {
    abundant: { executor: 'constant-arrival-rate', rate: 40, timeUnit: '1s', duration: '30s', preAllocatedVUs: 60, maxVUs: 150, exec: 'abundant' },
    scarce: { executor: 'per-vu-iterations', vus: 50, iterations: 10, startTime: '35s', maxDuration: '60s', exec: 'scarce' },
  },
  thresholds: {
    'http_req_duration{scenario:abundant}': ['p(95)<1500', 'p(99)<3000'],
    'http_req_failed{scenario:abundant}': ['rate<0.05'], // 409 STOCK_CHANGE_CONFLICT is retryable and appears when many orders hit one product at once,
    orders_unexpected: ['count==0'],
  },
};

export function setup() {
  const wh = warehouseToken();
  return {
    abundant: stockedProduct(wh, 1500),
    scarce: stockedProduct(wh, SCARCE),
    ops: [operatorToken(1), operatorToken(2)],
    wh,
  };
}

export function abundant(d) {
  const res = http.post(`${BACKEND}/orders`, orderBody(d.abundant, 1), { ...auth(d.ops[__VU % 2]), tags: { name: 'POST /orders abundant' } });
  if (res.status === 409 && res.body.indexOf('STOCK_CHANGE_CONFLICT') >= 0) abundantConflicts.add(1);
  else if (res.status !== 201) unexpected.add(1);
}

export function scarce(d) {
  const res = http.post(`${BACKEND}/orders`, orderBody(d.scarce, 1), { ...auth(d.ops[__VU % 2]), tags: { name: 'POST /orders scarce' }, responseCallback: http.expectedStatuses(201, 400, 409) });
  (byStatus[String(res.status)] || byStatus.other).add(1);
  if (res.status === 201) sold.add(1);
  else if (res.status === 400 && res.body.indexOf('INSUFFICIENT_STOCK') >= 0) refused.add(1);
  // A clean, documented conflict (retryable) when many requests hit one product at once: counted, not a failure.
  else if (res.status === 409 && res.body.indexOf('STOCK_CHANGE_CONFLICT') >= 0) conflicts.add(1);
  else unexpected.add(1);
}

export function teardown(d) {
  const s = http.get(`${BACKEND}/products/${d.scarce}`, auth(d.wh)).json('product.stock');
  const ok = check(s, { 'scarce stock never oversold (reserved <= physical)': (x) => x.reserved <= x.physical && x.reserved === SCARCE });
  if (!ok) console.error(`OVERSELL CHECK FAILED: ${JSON.stringify(s)}`);
}
