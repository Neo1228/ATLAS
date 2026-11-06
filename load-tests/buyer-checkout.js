import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const PROFILE = __ENV.PROFILE || '';
const RUN_ID = __ENV.RUN_ID || '';
const UNIT_PRICE_WON = Number(__ENV.UNIT_PRICE_WON || '');
const DURATION = __ENV.DURATION || '120s';
const SESSION_RATE_NUMERATOR = Number(__ENV.SESSION_RATE_NUMERATOR || '278');
const SESSION_RATE_TIME_UNIT = __ENV.SESSION_RATE_TIME_UNIT || '100s';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '';
const FIXED_VUS = Number(__ENV.FIXED_VUS || '');

function required(value, name) {
  if (!value) throw new Error(`${name} is required`);
}

function parseDurationSeconds(value, name) {
  const match = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(value);
  if (!match) throw new Error(`${name} must be a positive duration such as 120s`);
  const amount = Number(match[1]);
  const multiplier = { ms: 0.001, s: 1, m: 60, h: 3600 }[match[2]];
  const seconds = amount * multiplier;
  if (!Number.isFinite(seconds) || seconds <= 0) throw new Error(`${name} must be positive`);
  return seconds;
}

required(BASE_URL, 'BASE_URL');
required(PROFILE, 'PROFILE (one-seller or two-seller)');
required(RUN_ID, 'RUN_ID (unique synthetic-data prefix)');
if (!/^[A-Za-z0-9_-]{1,32}$/.test(RUN_ID)) {
  throw new Error('RUN_ID must match [A-Za-z0-9_-]{1,32}');
}
if (PROFILE !== 'one-seller' && PROFILE !== 'two-seller') {
  throw new Error('PROFILE must be one-seller or two-seller');
}
if (!Number.isSafeInteger(UNIT_PRICE_WON) || UNIT_PRICE_WON <= 0) {
  throw new Error('UNIT_PRICE_WON must be a positive integer');
}
if (!Number.isSafeInteger(SESSION_RATE_NUMERATOR) || SESSION_RATE_NUMERATOR <= 0) {
  throw new Error('SESSION_RATE_NUMERATOR must be a positive integer');
}
required(__ENV.FIXED_VUS, 'FIXED_VUS (derived from 1-VU calibration)');
if (!Number.isSafeInteger(FIXED_VUS) || FIXED_VUS < 1) {
  throw new Error('FIXED_VUS must be the positive integer derived from calibration');
}
const durationSeconds = parseDurationSeconds(DURATION, 'DURATION');
const sessionTimeUnitSeconds = parseDurationSeconds(SESSION_RATE_TIME_UNIT, 'SESSION_RATE_TIME_UNIT');
const initialStock = Math.ceil((SESSION_RATE_NUMERATOR / sessionTimeUnitSeconds) * durationSeconds);
if (!Number.isSafeInteger(initialStock) || initialStock < 1 || initialStock > 2147483647) {
  throw new Error('derived initialStock is outside the positive integer range supported by the API');
}

const EXPECTED_ORDER_COUNT = PROFILE === 'one-seller' ? 1 : 2;
const measuredHttpRequests = new Counter('measured_http_requests');
const sessionLatency = new Trend('checkout_session_duration', true);
const checkoutSessions = new Counter('completed_checkout_sessions');
const sessionErrors = new Counter('checkout_session_errors');
const sessionSamples = new Counter('checkout_session_samples');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    checkout_sessions: {
      executor: 'constant-arrival-rate',
      rate: SESSION_RATE_NUMERATOR,
      timeUnit: SESSION_RATE_TIME_UNIT,
      duration: DURATION,
      preAllocatedVUs: FIXED_VUS,
      maxVUs: FIXED_VUS,
      exec: 'checkoutSession',
      tags: { workload: 'buyer-checkout', profile: PROFILE },
    },
  },
};

function postCreated(path, body, operation) {
  const response = http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    tags: { phase: 'setup', operation },
  });
  if (response.status !== 201) {
    throw new Error(`setup ${operation} failed: HTTP ${response.status}`);
  }
  try {
    return response.json();
  } catch (_) {
    throw new Error(`setup ${operation} returned invalid JSON`);
  }
}

function createSellerProductSku(role, existingSellerId = null) {
  const seller = existingSellerId
    ? { sellerId: existingSellerId }
    : postCreated('/api/sellers', null, 'seed-seller');
  if (!seller.sellerId) throw new Error('setup seller response missing sellerId');
  const productName = `exp016-${RUN_ID}-${PROFILE}-${role}`;
  const product = postCreated(`/api/sellers/${seller.sellerId}/products`, {
    name: productName,
    description: `Synthetic EXP-016 seed ${RUN_ID} ${PROFILE} ${role}`,
  }, 'seed-product');
  if (!product.productId) throw new Error('setup product response missing productId');
  const skuId = `exp016-${RUN_ID}-${PROFILE}-${role}`;
  postCreated(`/api/sellers/${seller.sellerId}/products/${product.productId}/skus`, {
    skuId,
    priceWon: UNIT_PRICE_WON,
    initialStock,
  }, 'seed-sku');
  return { sellerId: seller.sellerId, skuId };
}

export function setup() {
  const lineA = createSellerProductSku('a');
  const lineB = PROFILE === 'one-seller'
    ? createSellerProductSku('b', lineA.sellerId)
    : createSellerProductSku('b');
  const seed = {
    profile: PROFILE,
    runId: RUN_ID,
    sellerAId: lineA.sellerId,
    sellerBId: lineB.sellerId,
    skuAId: lineA.skuId,
    skuBId: lineB.skuId,
    unitPriceWon: UNIT_PRICE_WON,
    initialStockPerSku: initialStock,
    plannedSessions: initialStock,
  };
  console.log(`EXP-016 seed: ${JSON.stringify(seed)}`);
  return seed;
}

function failed(reason) {
  sessionErrors.add(1, { profile: PROFILE, reason });
  return false;
}

function requestOk(response, label) {
  return check(response, { [`${label} HTTP 2xx`]: (r) => r.status >= 200 && r.status < 300 });
}

function countMeasuredRequest() {
  measuredHttpRequests.add(1, { profile: PROFILE });
}

export function checkoutSession(seed) {
  sessionSamples.add(1, { profile: PROFILE });
  const started = Date.now();
  const iteration = exec.scenario.iterationInTest;
  const buyerRef = `${RUN_ID}-buyer-${iteration}`;
  const idempotencyKey = `${RUN_ID}-checkout-${iteration}`;
  const buyer = encodeURIComponent(buyerRef);
  const headers = { 'Content-Type': 'application/json' };

  const cartA = http.put(
    `${BASE_URL}/api/buyers/${buyer}/cart/items/${seed.sellerAId}/${encodeURIComponent(seed.skuAId)}`,
    JSON.stringify({ quantity: 1 }), { headers, tags: { operation: 'cart-item-a', profile: PROFILE, phase: 'measure' } },
  );
  countMeasuredRequest();
  if (!requestOk(cartA, 'add SKU A')) return failed('cart_a');

  const cartB = http.put(
    `${BASE_URL}/api/buyers/${buyer}/cart/items/${seed.sellerBId}/${encodeURIComponent(seed.skuBId)}`,
    JSON.stringify({ quantity: 1 }), { headers, tags: { operation: 'cart-item-b', profile: PROFILE, phase: 'measure' } },
  );
  countMeasuredRequest();
  if (!requestOk(cartB, 'add SKU B')) return failed('cart_b');

  const checkout = http.post(
    `${BASE_URL}/api/buyers/${buyer}/cart/checkout`, null,
    { headers: { 'Idempotency-Key': idempotencyKey }, tags: { operation: 'checkout', profile: PROFILE, phase: 'measure' } },
  );
  countMeasuredRequest();
  if (!requestOk(checkout, 'checkout')) return failed('checkout');

  let checkoutBody;
  try {
    checkoutBody = checkout.json();
  } catch (_) {
    return failed('checkout_json');
  }
  const checkoutShapeOk = check(checkoutBody, {
    'checkout has id and expected child orders': (body) => Boolean(body.checkoutId)
      && Array.isArray(body.orders) && body.orders.length === EXPECTED_ORDER_COUNT,
    'checkout total matches two equal-price lines': (body) => body.totalWon === 2 * seed.unitPriceWon,
  });
  if (!checkoutShapeOk) return failed('checkout_shape_or_total');

  const payment = http.post(
    `${BASE_URL}/api/checkouts/${checkoutBody.checkoutId}/payment`, null,
    { tags: { operation: 'payment', profile: PROFILE, phase: 'measure' } },
  );
  countMeasuredRequest();
  if (!requestOk(payment, 'payment')) return failed('payment');

  let paymentBody;
  try {
    paymentBody = payment.json();
  } catch (_) {
    return failed('payment_json');
  }
  const paid = check(paymentBody, {
    'fixed fake PSP progression captures payment': (body) => body.paymentState === 'CAPTURED',
    'payment response retains expected child-order count': (body) => Array.isArray(body.orders)
      && body.orders.length === EXPECTED_ORDER_COUNT,
  });
  if (!paid) return failed('payment_state_or_orders');

  sessionLatency.add(Date.now() - started, { profile: PROFILE });
  checkoutSessions.add(1, { profile: PROFILE });
}

export function handleSummary(data) {
  const report = {
    profile: PROFILE,
    runId: RUN_ID,
    duration: DURATION,
    plannedSessionArrivalRate: `${SESSION_RATE_NUMERATOR}/${SESSION_RATE_TIME_UNIT} sessions/s`,
    fixedVUs: FIXED_VUS,
    initialStockPerSku: initialStock,
    httpRequestRps: data.metrics.measured_http_requests
      ? data.metrics.measured_http_requests.values.rate : null,
    completedCheckoutSessionTps: data.metrics.completed_checkout_sessions
      ? data.metrics.completed_checkout_sessions.values.rate : null,
    checkoutSessionLatencyMs: data.metrics.checkout_session_duration ? {
      p50: data.metrics.checkout_session_duration.values.med,
      p95: data.metrics.checkout_session_duration.values['p(95)'],
      p99: data.metrics.checkout_session_duration.values['p(99)'],
    } : null,
    checkoutSessionErrors: data.metrics.checkout_session_errors
      ? data.metrics.checkout_session_errors.values.count : 0,
    droppedIterations: data.metrics.dropped_iterations
      ? data.metrics.dropped_iterations.values.count : 0,
    attemptedSessionSamples: data.metrics.checkout_session_samples
      ? data.metrics.checkout_session_samples.values.count : 0,
    completedSessionSamples: data.metrics.completed_checkout_sessions
      ? data.metrics.completed_checkout_sessions.values.count : 0,
  };
  const output = JSON.stringify(report, null, 2);
  const destinations = { stdout: `${output}\n` };
  if (SUMMARY_FILE) destinations[SUMMARY_FILE] = `${output}\n`;
  return destinations;
}
