// Shared helpers for the k6 scenarios (load/README in docs/LOAD_TESTING.md).
import http from 'k6/http';
import { check, fail } from 'k6';

export const BACKEND = __ENV.BACKEND_URL || 'http://backend:8080';
export const SUGGESTIONS = __ENV.SUGGESTIONS_URL || 'http://suggestions:8080';
export const DEMO_PASSWORD = 'Demo1234!';
export const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function login(email, password) {
  const res = http.post(`${BACKEND}/auth/login`, JSON.stringify({ email, password }), { headers: JSON_HEADERS, tags: { name: 'POST /auth/login' } });
  return res;
}

export function token(email, password) {
  const res = login(email, password);
  if (res.status !== 200) fail(`login ${email} -> ${res.status} ${res.body}`);
  return res.json('token');
}

export function auth(t) {
  return { headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' } };
}

export function adminToken() {
  return token(__ENV.ADMIN_EMAIL, __ENV.ADMIN_PASSWORD);
}

export function warehouseToken() {
  return token('warehouse@smartwarehouse.local', DEMO_PASSWORD);
}

export function operatorToken(n) {
  return token(n === 2 ? 'operator2@smartwarehouse.local' : 'operator1@smartwarehouse.local', DEMO_PASSWORD);
}

export function rnd(n) {
  return Math.floor(Math.random() * n);
}

export function uid() {
  return Math.random().toString(16).slice(2, 12) + Date.now().toString(16);
}

// A product with `qty` units in one fresh position (own zone and line), like e2e/suite/lib/factory.py.
export function stockedProduct(t, qty) {
  const a = auth(t);
  const post = (path, body) => {
    const r = http.post(`${BACKEND}${path}`, JSON.stringify(body), a);
    if (r.status !== 201) fail(`${path} -> ${r.status} ${r.body}`);
    return r.json();
  };
  const patch = (path, body) => {
    const r = http.patch(`${BACKEND}${path}`, JSON.stringify(body), a);
    if (r.status !== 200) fail(`${path} -> ${r.status} ${r.body}`);
    return r.json();
  };
  const sku = `LOAD-${uid().toUpperCase()}`;
  const product = post('/products', {
    sku, name: `Load ${sku}`, category: 'OTROS', description: 'load test', height: 10, width: 10, length: 10,
    weight: 1, max_quantity_per_order: 50, minimum_stock: 0,
    price: { amount_cents: 1999, currency: 'USD', tax_included: true },
  }).product;
  const zone = patch(`/warehouse/zones/${post('/warehouse/zones', { zone_code: `ZL${uid().slice(0, 6).toUpperCase()}`, max_allowed_lines: 5 }).id_zone}`, { is_active: true });
  const line = patch(`/warehouse/lines/${post(`/warehouse/zones/${zone.id_zone}/lines`, { number_line: 1, max_allowed_positions: 20 }).id_line}`, { is_active: true });
  const pos = post(`/warehouse/lines/${line.id_line}/positions`, { position_name: 'P01', maximum_capacity: Math.max(qty, 500), size_stock_to_save: 'PALLET', qr_code: `LOAD|${uid()}` });
  patch(`/warehouse/positions/${pos.id_position}`, { product_id: product.id, current_stock: qty, is_active: true });
  return product.id;
}

export function orderBody(productId, qty) {
  return JSON.stringify({
    items: [{ product_id: productId, quantity: qty }], destination_area: 'LOAD-AREA',
    address: { street: '1 Load St', department: 'QA', floor: '1', postal_code: '00001' },
  });
}

export function ok2xx(res, name) {
  return check(res, { [`${name} 2xx`]: (r) => r.status >= 200 && r.status < 300 });
}
