// Login burst: a spike of logins (bcrypt is CPU bound) then a steady rate.
import { check } from 'k6';
import { login, DEMO_PASSWORD } from './lib.js';

export const options = {
  scenarios: {
    burst: { executor: 'ramping-arrival-rate', startRate: 5, timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 300,
      stages: [{ target: 50, duration: '10s' }, { target: 50, duration: '20s' }, { target: 5, duration: '5s' }] },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:POST /auth/login}': ['p(95)<1500', 'p(99)<3000'],
  },
};

export default function () {
  const n = 1 + Math.floor(Math.random() * 4);
  const res = login(`operator${n}@smartwarehouse.local`, DEMO_PASSWORD);
  check(res, { 'login 200': (r) => r.status === 200 });
}
