// Metrics endpoints over the seeded 730 days: /metrics/query (VictoriaMetrics), suggestion-conversion
// (day/week/month buckets) and restock-suggestions (computed from the order history).
import http from 'k6/http';
import { check } from 'k6';
import { BACKEND, auth, warehouseToken, adminToken, rnd } from './lib.js';

export const options = {
  scenarios: {
    metrics: { executor: 'constant-vus', vus: Number(__ENV.VUS || 8), duration: __ENV.DURATION || '40s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:POST /metrics/suggestion-conversion}': ['p(95)<3000'],
    'http_req_duration{name:POST /metrics/restock-suggestions}': ['p(95)<5000'],
    'http_req_duration{name:POST /metrics/query}': ['p(95)<2000'],
  },
};

const iso = (d) => d.toISOString().replace(/\.\d+Z$/, 'Z');

export function setup() {
  return { wh: warehouseToken(), admin: adminToken() };
}

export default function (d) {
  const now = new Date();
  const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()) - 729 * 86400000);
  const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()) + 86400000);
  const roll = Math.random();
  let res;
  if (roll < 0.4) {
    const g = ['day', 'week', 'month'][rnd(3)];
    res = http.post(`${BACKEND}/metrics/suggestion-conversion`, JSON.stringify({ params: { from: iso(start), to: iso(end), granularity: g } }),
      { ...auth(d.wh), tags: { name: 'POST /metrics/suggestion-conversion' } });
  } else if (roll < 0.7) {
    res = http.post(`${BACKEND}/metrics/restock-suggestions`, JSON.stringify({ params: { alpha: 0.3, recent_days: 7, long_days: 60, safety_days: 2, lead_time_days: 5, coverage_days: 7 } }),
      { ...auth(d.wh), tags: { name: 'POST /metrics/restock-suggestions' } });
  } else {
    const to = new Date();
    const from = new Date(to.getTime() - 6 * 3600 * 1000);
    res = http.post(`${BACKEND}/metrics/query`, JSON.stringify({ metric: 'wh.vehicle.battery', from: iso(from), to: iso(to), step: '5m', agg: 'avg', filters: {}, group_by: [] }),
      { ...auth(d.admin), tags: { name: 'POST /metrics/query' } });
  }
  check(res, { '2xx': (r) => r.status >= 200 && r.status < 300 });
}
