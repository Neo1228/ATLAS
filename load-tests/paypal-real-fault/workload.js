import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

// Syntax-only, with no input files or provider access: k6 inspect workload.js
// Run via run.sh only. buyers.json is an array of {"checkoutId":"UUID","token":"JWT"};
// each checkout must belong to its token's buyer and contain a pre-created, payable
// checkout for the intended seller/SKU. No checkout, approval, return, capture,
// reconcile or cancel is issued here. Never reuse this input for another run.
const active = __ENV.REAL_SANDBOX_OPT_IN === 'YES';
const integer = (name, fallback, max) => {
  const s = __ENV[name] || String(fallback);
  if (!/^[1-9][0-9]*$/.test(s) || Number(s) > max) throw new Error(`Invalid ${name}`);
  return Number(s);
};
const duration = integer('DURATION_SECONDS', 30, 300);
const paymentRate = integer('PAYMENT_RATE', 1, 10);
const catalogRate = integer('CATALOG_RATE', 5, 50);
const base = __ENV.BASE_URL || 'http://127.0.0.1:18080';
if (!/^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(base) || Number(base.split(':')[2]) > 65535) {
  throw new Error('BASE_URL must be a loopback IPv4 HTTP origin with a port and no userinfo');
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
let buyers = [], seller = '';
if (active) {
  if (!__ENV.BUYERS_FILE || !__ENV.SELLER_ID_FILE) throw new Error('Input files are required');
  buyers = JSON.parse(open(__ENV.BUYERS_FILE));
  seller = open(__ENV.SELLER_ID_FILE).trim();
  if (!uuid.test(seller) || !Array.isArray(buyers) || buyers.length < paymentRate * duration) {
    throw new Error('A seller UUID and at least PAYMENT_RATE * DURATION_SECONDS buyers are required');
  }
  const seen = new Set([seller.toLowerCase()]);
  for (const row of buyers) {
    if (!row || typeof row.checkoutId !== 'string' || !uuid.test(row.checkoutId) ||
        seen.has(row.checkoutId.toLowerCase()) || typeof row.token !== 'string' ||
        !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(row.token)) {
      throw new Error('Invalid or identical IDs, or invalid JWT in buyers file');
    }
    seen.add(row.checkoutId.toLowerCase());
  }
}
export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['count', 'avg', 'p(50)', 'p(95)', 'p(99)'],
  scenarios: {
    catalog: { executor: 'constant-arrival-rate', exec: 'catalog', rate: catalogRate, timeUnit: '1s', duration: `${duration}s`, preAllocatedVUs: 20, maxVUs: 60 },
    payment: { executor: 'constant-arrival-rate', exec: 'payment', rate: paymentRate, timeUnit: '1s', duration: `${duration}s`, preAllocatedVUs: 20, maxVUs: 100 },
  },
};
const metrics = {};
for (const kind of ['catalog', 'payment']) {
  metrics[kind] = {
    good: new Counter(`${kind}_good`), errors: new Rate(`${kind}_errors`),
    success: new Trend(`${kind}_success_ms`, true), client_error: new Trend(`${kind}_client_error_ms`, true),
    server_error: new Trend(`${kind}_server_error_ms`, true), transport: new Trend(`${kind}_transport_ms`, true),
    other: new Trend(`${kind}_other_ms`, true),
  };
}
// Success and goodput are strictly HTTP 2xx, not proof of provider success,
// approval, capture, or settlement; payment responses are deliberately discarded.
function record(kind, response, elapsed) {
  const outcome = response.status === 0 ? 'transport' : response.status >= 200 && response.status < 300 ? 'success' :
    response.status >= 400 && response.status < 500 ? 'client_error' : response.status >= 500 ? 'server_error' : 'other';
  const m = metrics[kind];
  m[outcome].add(elapsed);
  m.errors.add(outcome !== 'success');
  if (outcome === 'success') m.good.add(1);
}
function requireOptIn() { if (!active) throw new Error('Set REAL_SANDBOX_OPT_IN=YES and use run.sh'); }
export function catalog() {
  requireOptIn();
  const start = Date.now();
  const r = http.get(`${base}/api/products?sellerId=${seller}&limit=10`, { timeout: '15s', tags: { name: 'catalog' } });
  record('catalog', r, Date.now() - start);
}
export function payment() {
  requireOptIn();
  const buyer = buyers[exec.scenario.iterationInTest];
  if (!buyer) throw new Error('Checkout input exhausted');
  const start = Date.now();
  const r = http.post(`${base}/api/checkouts/${buyer.checkoutId}/payment`, null, {
    headers: { Authorization: `Bearer ${buyer.token}` }, timeout: '30s', tags: { name: 'payment' },
  });
  record('payment', r, Date.now() - start);
}
export function handleSummary(data) {
  // Rates are over full wall-clock test time including graceful drain. k6's
  // dropped_iterations is aggregate; scenario tags in external outputs split it.
  const seconds = data.state.testRunDurationMs / 1000;
  const goodput = {};
  for (const kind of ['catalog', 'payment']) {
    const metric = data.metrics[`${kind}_good`];
    goodput[kind] = seconds > 0 && metric ? metric.values.count / seconds : 0;
  }
  return { stdout: JSON.stringify({ duration_seconds: seconds, goodput_per_second: goodput, metrics: data.metrics }, null, 2) + '\n' };
}
