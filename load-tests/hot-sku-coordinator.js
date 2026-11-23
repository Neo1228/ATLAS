// Hot-SKU flash-sale flood against the PostgreSQL-only order coordinator.
//
// Every iteration submits ONE order for ONE unit of ONE shared SKU through
// POST /api/internal/order-inventory/reservations. The SKU is seeded with STOCK units, so once the
// stock is gone every further order is a legitimate rejection (HTTP 409, workflow FAILED).
// The scenario records the outcome mix, the submit latency per outcome, and checks the stock invariants after
// the run. It is deliberately a sibling of distributed-coordinator.js, which spreads each order over 3 SKUs.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const COORDINATOR_URL = __ENV.COORDINATOR_URL;
const COORDINATOR_TOKEN = __ENV.ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN;
const INVENTORY_BASE_URL = __ENV.INVENTORY_LOCATION_BASE_URL;
const INVENTORY_TOKEN = __ENV.INVENTORY_LOCATION_SERVICE_TOKEN;
const LOCATION_ID = __ENV.FULFILLMENT_LOCATION_ID;
const SELLER_ID = '00000000-0000-0000-0000-000000000011';
const RUN_ID = __ENV.RUN_ID;
const STOCK = Number(__ENV.STOCK || '100');
const RATE = Number(__ENV.RATE || '100');
const DURATION = __ENV.DURATION || '20s';
const PRE_VUS = 300;
const MAX_VUS = 3000;
const SUMMARY_FILE = __ENV.SUMMARY_FILE;

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
for (const [name, value] of Object.entries({
  COORDINATOR_URL, ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN: COORDINATOR_TOKEN, INVENTORY_LOCATION_BASE_URL: INVENTORY_BASE_URL,
  INVENTORY_LOCATION_SERVICE_TOKEN: INVENTORY_TOKEN, FULFILLMENT_LOCATION_ID: LOCATION_ID, RUN_ID,
})) {
  if (!value) throw new Error(`${name} is required`);
}
for (const [name, value] of Object.entries({ FULFILLMENT_LOCATION_ID: LOCATION_ID, SELLER_ID, RUN_ID })) {
  if (!UUID_PATTERN.test(value)) throw new Error(`${name} must be a UUID`);
}
if (!Number.isSafeInteger(STOCK) || STOCK < 1) throw new Error('STOCK must be a positive integer');
if (!Number.isSafeInteger(RATE) || RATE < 1) throw new Error('RATE must be a positive integer');
if (!/^\d+(s|m)$/.test(DURATION)) throw new Error('DURATION must look like 20s or 1m');

const coordinatorHeaders = { 'X-Atlas-Service-Token': COORDINATOR_TOKEN, 'Content-Type': 'application/json' };
const inventoryHeaders = { 'X-Atlas-Service-Token': INVENTORY_TOKEN, 'Content-Type': 'application/json' };

const STATUS_CODES = [0, 200, 201, 202, 400, 401, 403, 404, 408, 409, 422, 429, 500, 502, 503, 504];
const statusCounters = new Map(STATUS_CODES.map((s) => [s, new Counter(`coordinator_http_status_${s}`)]));
const otherStatus = new Counter('coordinator_http_status_other');
const reservedLatency = new Trend('coordinator_submit_latency_reserved', true);
const rejectedLatency = new Trend('coordinator_submit_latency_rejected', true);
const otherLatency = new Trend('coordinator_submit_latency_other', true);
const allLatency = new Trend('coordinator_submit_latency_all', true);

// 201/200 = RESERVED, 409 = FAILED/NEEDS_REVIEW (rejection), 202 = still pending: all are "expected" outcomes.
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    hot_sku_coordinator: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
      tags: { workload: 'hot-sku-coordinator' },
    },
  },
};

function stockUrl(skuId) {
  return `${INVENTORY_BASE_URL}/internal/v1/fulfillment-locations/${LOCATION_ID}/stock/${SELLER_ID}/${skuId}`;
}

function skuIdFor() {
  return `hot-coordinator-${RUN_ID}`;
}

function readStock(skuId) {
  const response = http.get(stockUrl(skuId), { headers: inventoryHeaders, tags: { phase: 'teardown', operation: 'stock-read' } });
  if (response.status !== 200) return null;
  return response.json();
}

export function setup() {
  const skuId = skuIdFor();
  const response = http.put(
    stockUrl(skuId),
    JSON.stringify({ sellerId: SELLER_ID, initialCapacity: STOCK }),
    { headers: inventoryHeaders, tags: { phase: 'setup', operation: 'stock-seed' } },
  );
  if (response.status !== 200) fail(`stock setup failed: HTTP ${response.status}`);
  return { skuId };
}

function orderIdForIteration() {
  const prefix = RUN_ID.replace(/[^0-9a-f]/gi, '').slice(0, 8).padStart(8, '0');
  const suffix = `${String(__VU).padStart(6, '0')}${String(__ITER).padStart(6, '0')}`.slice(-12);
  return `${prefix}-0000-4000-8000-${suffix}`;
}

export default function (seed) {
  const body = JSON.stringify({
    orderId: orderIdForIteration(),
    allocations: [{
      fulfillmentLocationId: LOCATION_ID,
      items: [{ sellerId: SELLER_ID, skuId: seed.skuId, quantity: 1 }],
    }],
  });
  const response = http.post(COORDINATOR_URL, body, {
    headers: { ...coordinatorHeaders, 'Idempotency-Key': `${RUN_ID}-${__VU}-${__ITER}` },
    tags: { phase: 'measure', operation: 'coordinator-submit' },
  });
  (statusCounters.get(response.status) || otherStatus).add(1);
  const latency = response.timings.duration;
  allLatency.add(latency);
  if (response.status === 200 || response.status === 201) {
    reservedLatency.add(latency);
  } else if (response.status === 409) {
    rejectedLatency.add(latency);
  } else {
    otherLatency.add(latency);
  }
}

export function teardown(seed) {
  const stock = readStock(seed.skuId);
  if (stock === null) {
    console.log('INVARIANT ' + JSON.stringify({ readable: false }));
    return;
  }
  const conserved = stock.available + stock.reserved + stock.claimed === stock.initialCapacity;
  const notOversold = stock.reserved + stock.claimed <= stock.initialCapacity && stock.available >= 0;
  console.log('INVARIANT ' + JSON.stringify({
    readable: true,
    initialCapacity: stock.initialCapacity,
    available: stock.available,
    reserved: stock.reserved,
    claimed: stock.claimed,
    capacityConserved: conserved,
    notOversold,
  }));
  check(stock, { 'capacity conserved': () => conserved, 'not oversold': () => notOversold });
}

function metricValue(data, name, key) {
  const metric = data.metrics[name];
  return metric && metric.values && metric.values[key] !== undefined ? metric.values[key] : null;
}

function trendSummary(data, name) {
  const keys = ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];
  const out = { count: metricValue(data, name, 'count') };
  for (const key of keys) out[key] = metricValue(data, name, key);
  return out;
}

export function handleSummary(data) {
  const statusCounts = {};
  for (const name of Object.keys(data.metrics)) {
    if (name.startsWith('coordinator_http_status_')) {
      const count = metricValue(data, name, 'count');
      if (count) statusCounts[name.replace('coordinator_http_status_', '')] = count;
    }
  }
  const iterations = metricValue(data, 'iterations', 'count') || 0;
  const seconds = data.state && data.state.testRunDurationMs ? data.state.testRunDurationMs / 1000 : null;
  const summary = {
    runId: RUN_ID,
    offeredRate: RATE,
    duration: DURATION,
    stock: STOCK,
    iterations,
    droppedIterations: metricValue(data, 'dropped_iterations', 'count') || 0,
    completedPerSecond: metricValue(data, 'iterations', 'rate'),
    testRunSeconds: seconds,
    vusMax: metricValue(data, 'vus_max', 'max'),
    statusCounts,
    latencyMs: {
      all: trendSummary(data, 'coordinator_submit_latency_all'),
      reserved: trendSummary(data, 'coordinator_submit_latency_reserved'),
      rejected: trendSummary(data, 'coordinator_submit_latency_rejected'),
      other: trendSummary(data, 'coordinator_submit_latency_other'),
    },
  };
  const outputs = { stdout: '' };
  if (SUMMARY_FILE) outputs[SUMMARY_FILE] = JSON.stringify(summary, null, 2);
  return outputs;
}
