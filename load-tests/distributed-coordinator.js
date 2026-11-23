import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 서비스 주소와 id는 compose·README의 고정 값과 같아야 하므로 상수로 둔다.
// 실행마다 달라지는 값(rate, duration, VU 상한, 재시도 횟수, 비밀값, RUN_ID)만 환경변수로 받는다.
const INVENTORY_BASE_URL_A = 'http://host.docker.internal:8090';
const INVENTORY_BASE_URL_B = 'http://host.docker.internal:8091';
const LOCATION_ID_A = '00000000-0000-0000-0000-000000000001';
const LOCATION_ID_B = '00000000-0000-0000-0000-000000000002';
const SELLER_ID_A = '00000000-0000-0000-0000-000000000011';
const SELLER_ID_B = '00000000-0000-0000-0000-000000000012';
const RETRY_DELAY_MS = 50;

const COORDINATOR_URL = (__ENV.COORDINATOR_URL || '').replace(/\/$/, '');
const COORDINATOR_TOKEN = __ENV.ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN || '';
const INVENTORY_TOKEN = __ENV.INVENTORY_LOCATION_SERVICE_TOKEN || '';
const RUN_ID = __ENV.RUN_ID || '';
const RATE = Number(__ENV.RATE || '1000');
const DURATION = __ENV.DURATION || '30s';
const LOCATION_COUNT = Number(__ENV.LOCATIONS || '2');
const MAX_VUS = Number(__ENV.MAX_VUS || '1000');
const PRE_ALLOCATED_VUS = Math.min(100, MAX_VUS);
const TRANSPORT_RETRIES = Number(__ENV.TRANSPORT_RETRIES || '0');

function required(value, name) {
  if (!value) throw new Error(`${name} is required`);
}

function uuid(value, name) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) {
    throw new Error(`${name} must be a UUID`);
  }
  return value;
}

function durationSeconds(value) {
  const match = /^(\d+)(s|m)$/.exec(value);
  if (!match) throw new Error('DURATION must be an integer duration such as 30s');
  const multiplier = match[2] === 'm' ? 60 : 1;
  return Number(match[1]) * multiplier;
}

required(COORDINATOR_URL, 'COORDINATOR_URL');
required(COORDINATOR_TOKEN, 'ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN');
required(INVENTORY_TOKEN, 'INVENTORY_LOCATION_SERVICE_TOKEN');
required(RUN_ID, 'RUN_ID (unique per benchmark run)');
uuid(RUN_ID, 'RUN_ID');
if (!Number.isSafeInteger(RATE) || RATE < 1) throw new Error('RATE must be a positive integer');
if (LOCATION_COUNT !== 1 && LOCATION_COUNT !== 2) throw new Error('LOCATIONS must be 1 or 2');
if (!Number.isSafeInteger(MAX_VUS) || MAX_VUS < 1) throw new Error('MAX_VUS must be a positive integer');
if (!Number.isSafeInteger(TRANSPORT_RETRIES) || TRANSPORT_RETRIES < 0 || TRANSPORT_RETRIES > 2) {
  throw new Error('TRANSPORT_RETRIES must be 0, 1, or 2');
}
const runDurationSeconds = durationSeconds(DURATION);

const expectedOrders = RATE * runDurationSeconds;
const initialCapacity = Math.ceil(expectedOrders * 1.1);
if (!Number.isSafeInteger(expectedOrders) || !Number.isSafeInteger(initialCapacity)
    || initialCapacity > 2147483647) {
  throw new Error('Derived stock capacity is outside the positive integer range supported by the API');
}
const coordinatorHeaders = {
  'X-Atlas-Service-Token': COORDINATOR_TOKEN,
  'Content-Type': 'application/json',
};
const inventoryHeaders = {
  'X-Atlas-Service-Token': INVENTORY_TOKEN,
  'Content-Type': 'application/json',
};
const reserved = new Counter('coordinator_reservations_reserved');
const pending = new Counter('coordinator_reservations_pending');
const rejected = new Counter('coordinator_reservations_rejected');
const idempotentRetries = new Counter('coordinator_idempotent_retries');
const idempotentRetriesRecovered = new Counter('coordinator_idempotent_retries_recovered');
const idempotentRetriesUnrecovered = new Counter('coordinator_idempotent_retries_unrecovered');
const idempotentReplays = new Counter('coordinator_idempotent_replays');
const responseStatusCodes = [0, 200, 201, 202, 400, 401, 403, 404, 408, 409, 422, 429, 500, 502, 503, 504];
const responseStatusCounters = new Map(responseStatusCodes.map((status) =>
  [status, new Counter(`coordinator_http_status_${status}`)]));
const otherResponseStatus = new Counter('coordinator_http_status_other');

http.setResponseCallback(http.expectedStatuses(200, 201, 202));

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    coordinator_reservations: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      tags: { workload: 'distributed-coordinator', locations: String(LOCATION_COUNT) },
    },
  },
  thresholds: { checks: ['rate > 0.99'] },
};

function stockUrl(baseUrl, locationId, sellerId, skuId) {
  return `${baseUrl}/internal/v1/fulfillment-locations/${locationId}/stock/${sellerId}/${skuId}`;
}

function seedStock(baseUrl, locationId, sellerId, skuId) {
  const response = http.put(stockUrl(baseUrl, locationId, sellerId, skuId), JSON.stringify({
    sellerId,
    initialCapacity,
  }), { headers: inventoryHeaders, tags: { phase: 'setup', operation: 'stock-seed' } });
  if (response.status !== 200) fail(`stock setup failed: HTTP ${response.status}`);
}

export function setup() {
  const skuA1 = `coordinator-a1-${RUN_ID}`;
  const skuA2 = `coordinator-a2-${RUN_ID}`;
  const skuA3 = `coordinator-a3-${RUN_ID}`;
  const skuB1 = `coordinator-b1-${RUN_ID}`;
  seedStock(INVENTORY_BASE_URL_A, LOCATION_ID_A, SELLER_ID_A, skuA1);
  seedStock(INVENTORY_BASE_URL_A, LOCATION_ID_A, SELLER_ID_A, skuA2);
  if (LOCATION_COUNT === 1) {
    seedStock(INVENTORY_BASE_URL_A, LOCATION_ID_A, SELLER_ID_A, skuA3);
  } else {
    seedStock(INVENTORY_BASE_URL_B, LOCATION_ID_B, SELLER_ID_B, skuB1);
  }
  return { skuA1, skuA2, skuA3, skuB1 };
}

function orderIdForIteration() {
  const prefix = RUN_ID.replace(/[^0-9a-f]/gi, '').slice(0, 8).padStart(8, '0');
  const suffix = `${String(__VU).padStart(6, '0')}${String(__ITER).padStart(6, '0')}`.slice(-12);
  return `${prefix}-0000-4000-8000-${suffix}`;
}

function submitCoordinator(requestBody, idempotencyKey) {
  const response = http.post(COORDINATOR_URL, requestBody, {
    headers: {
      ...coordinatorHeaders,
      'Idempotency-Key': idempotencyKey,
    },
    tags: { phase: 'measure', operation: 'coordinator-submit' },
  });
  const statusCounter = responseStatusCounters.get(response.status) || otherResponseStatus;
  if (response.status === 200) idempotentReplays.add(1);
  statusCounter.add(1);
  return response;
}

export default function (seed) {
  const itemsA = [
    { sellerId: SELLER_ID_A, skuId: seed.skuA1, quantity: 1 },
    { sellerId: SELLER_ID_A, skuId: seed.skuA2, quantity: 1 },
  ];
  const allocations = [{ fulfillmentLocationId: LOCATION_ID_A, items: itemsA }];
  if (LOCATION_COUNT === 1) {
    itemsA.push({ sellerId: SELLER_ID_A, skuId: seed.skuA3, quantity: 1 });
  } else {
    allocations.push({
      fulfillmentLocationId: LOCATION_ID_B,
      items: [{ sellerId: SELLER_ID_B, skuId: seed.skuB1, quantity: 1 }],
    });
  }

  const idempotencyKey = `${RUN_ID}-${__VU}-${__ITER}`;
  const requestBody = JSON.stringify({ orderId: orderIdForIteration(), allocations });
  let response = submitCoordinator(requestBody, idempotencyKey);
  let retries = 0;
  while ((response.status === 0 || response.status >= 500) && retries < TRANSPORT_RETRIES) {
    retries++;
    idempotentRetries.add(1);
    sleep((RETRY_DELAY_MS * retries) / 1000);
    response = submitCoordinator(requestBody, idempotencyKey);
  }
  if (retries > 0) {
    if (response.status === 200 || response.status === 201 || response.status === 202) {
      idempotentRetriesRecovered.add(1);
    } else {
      idempotentRetriesUnrecovered.add(1);
    }
  }

  const accepted = check(response, {
    'coordinator accepts workflow': (r) => r.status === 200 || r.status === 201 || r.status === 202,
  });
  if (!accepted) {
    rejected.add(1);
    return;
  }

  let body;
  try {
    body = response.json();
  } catch (_) {
    rejected.add(1);
    return;
  }
  if ((response.status === 200 || response.status === 201) && body.status === 'RESERVED') {
    reserved.add(1);
  } else {
    pending.add(1);
  }
}

function readStock(baseUrl, locationId, sellerId, skuId) {
  const response = http.get(stockUrl(baseUrl, locationId, sellerId, skuId), {
    headers: { 'X-Atlas-Service-Token': INVENTORY_TOKEN },
    tags: { phase: 'teardown', operation: 'stock-read' },
  });
  if (!check(response, { 'stock read succeeds': (r) => r.status === 200 })) return null;
  return response.json();
}

export function teardown(seed) {
  const rowsA = [seed.skuA1, seed.skuA2, ...(LOCATION_COUNT === 1 ? [seed.skuA3] : [])];
  const stocksA = rowsA.map((skuId) => readStock(INVENTORY_BASE_URL_A, LOCATION_ID_A, SELLER_ID_A, skuId));
  const stocksB = LOCATION_COUNT === 1 ? [] : [readStock(INVENTORY_BASE_URL_B, LOCATION_ID_B, SELLER_ID_B, seed.skuB1)];
  const stocks = [...stocksA, ...stocksB].filter(Boolean);
  check(stocks, {
    'seeded inventory capacity is conserved': (rows) => rows.every((stock) =>
      stock.initialCapacity === stock.available + stock.reserved + stock.claimed),
  });
}
