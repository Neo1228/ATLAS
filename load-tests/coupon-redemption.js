import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const RUN_ID = __ENV.RUN_ID || '';
const MODE = __ENV.MODE || '';
const VUS = Number(__ENV.VUS || '');
const ITERATIONS_PER_VU = Number(__ENV.ITERATIONS_PER_VU || '1');
const ATTEMPTS = VUS * ITERATIONS_PER_VU;
const UNIT_PRICE_WON = Number(__ENV.UNIT_PRICE_WON || '');
const DISCOUNT_WON = Number(__ENV.DISCOUNT_WON || '');
const MAX_REDEMPTIONS = Number(__ENV.MAX_REDEMPTIONS || '');
const EXPECTED_WINNERS = MODE === 'limited' ? MAX_REDEMPTIONS : ATTEMPTS;
const EXPECTED_CONFLICTS = MODE === 'limited' ? ATTEMPTS - MAX_REDEMPTIONS : 0;

function requireValue(value, name) {
  if (value === '' || value === null || value === undefined) throw new Error(`${name} is required`);
}

requireValue(BASE_URL, 'BASE_URL');
requireValue(RUN_ID, 'RUN_ID');
requireValue(MODE, 'MODE (unlimited or limited)');
requireValue(__ENV.VUS, 'VUS');
if (!/^[A-Za-z0-9_-]{1,31}$/.test(RUN_ID)) throw new Error('RUN_ID must match [A-Za-z0-9_-]{1,31}');
if (MODE !== 'unlimited' && MODE !== 'limited') throw new Error('MODE must be unlimited or limited');
if (!Number.isSafeInteger(VUS) || VUS < 1) throw new Error('VUS must be a positive integer');
if (!Number.isSafeInteger(ITERATIONS_PER_VU) || ITERATIONS_PER_VU < 1) {
  throw new Error('ITERATIONS_PER_VU must be a positive integer');
}
if (!Number.isSafeInteger(ATTEMPTS) || ATTEMPTS < 2) throw new Error('VUS * ITERATIONS_PER_VU must be >= 2');
if (!Number.isSafeInteger(UNIT_PRICE_WON) || UNIT_PRICE_WON <= 0) throw new Error('UNIT_PRICE_WON must be positive');
if (!Number.isSafeInteger(DISCOUNT_WON) || DISCOUNT_WON <= 0 || DISCOUNT_WON >= UNIT_PRICE_WON) {
  throw new Error('DISCOUNT_WON must be positive and less than UNIT_PRICE_WON');
}
if (MODE === 'limited' && (!Number.isSafeInteger(MAX_REDEMPTIONS)
    || MAX_REDEMPTIONS <= 0 || MAX_REDEMPTIONS > ATTEMPTS)) {
  throw new Error('MAX_REDEMPTIONS must be positive and no greater than ATTEMPTS');
}

const attempts = new Counter('coupon_checkout_attempts');
const measuredHttpRequests = new Counter('coupon_measured_http_requests');
const winners = new Counter('coupon_checkout_winners');
const conflicts = new Counter('coupon_capacity_conflicts');
const captures = new Counter('coupon_checkout_captures');
const unexpectedErrors = new Counter('coupon_test_unexpected_errors');
const checkoutHttpLatency = new Trend('coupon_checkout_http_latency', true);
const acceptedCheckoutLatency = new Trend('coupon_accepted_checkout_latency', true);
const capConflictLatency = new Trend('coupon_cap_conflict_latency', true);
const acceptedSessionLatency = new Trend('coupon_accepted_session_latency', true);

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    coupon_redemption_race: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERATIONS_PER_VU,
      exec: 'couponCheckout',
      maxDuration: '60s',
      tags: { workload: 'coupon-redemption', mode: MODE },
    },
  },
  thresholds: {
    coupon_checkout_attempts: [`count==${ATTEMPTS}`],
    coupon_checkout_winners: [`count==${EXPECTED_WINNERS}`],
    coupon_capacity_conflicts: [`count==${EXPECTED_CONFLICTS}`],
    coupon_checkout_captures: [`count==${EXPECTED_WINNERS}`],
    coupon_test_unexpected_errors: ['count==0'],
  },
};

function postCreated(path, body, operation) {
  const response = http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    tags: { phase: 'setup', operation, workload: 'coupon-redemption', mode: MODE },
  });
  if (response.status !== 201) throw new Error(`setup ${operation} failed: HTTP ${response.status}`);
  return response.json();
}

export function setup() {
  const seller = postCreated('/api/sellers', null, 'seed-seller');
  const skuId = `cap-${RUN_ID}`;
  const product = postCreated(`/api/sellers/${seller.sellerId}/products`, {
    name: `Coupon capacity ${RUN_ID}`,
    description: `Synthetic local redemption workload ${RUN_ID}`,
  }, 'seed-product');
  postCreated(`/api/sellers/${seller.sellerId}/products/${product.productId}/skus`, {
    skuId,
    priceWon: UNIT_PRICE_WON,
    initialStock: ATTEMPTS,
  }, 'seed-sku');

  const couponCode = `C${RUN_ID}`.slice(0, 32).toUpperCase();
  const now = Date.now();
  const coupon = {
    code: couponCode,
    discountAmountWon: DISCOUNT_WON,
    startsAt: new Date(now - 60_000).toISOString(),
    endsAt: new Date(now + 3_600_000).toISOString(),
  };
  if (MODE === 'limited') coupon.maxRedemptions = MAX_REDEMPTIONS;
  postCreated(`/api/sellers/${seller.sellerId}/coupons`, coupon, 'seed-coupon');
  return { sellerId: seller.sellerId, skuId, couponCode };
}

export function couponCheckout(seed) {
  const iteration = exec.scenario.iterationInTest;
  const buyerRef = `${RUN_ID}-buyer-${iteration}`;
  const idempotencyKey = `${RUN_ID}-checkout-${iteration}`;
  const headers = { 'Content-Type': 'application/json' };
  attempts.add(1, { mode: MODE });

  const cart = http.put(`${BASE_URL}/api/buyers/${encodeURIComponent(buyerRef)}/cart/items/`
      + `${seed.sellerId}/${encodeURIComponent(seed.skuId)}`,
  JSON.stringify({ quantity: 1 }), {
    headers,
    tags: { phase: 'measure', operation: 'cart-item', mode: MODE },
  });
  measuredHttpRequests.add(1, { mode: MODE, operation: 'cart-item' });
  if (cart.status !== 200) {
    unexpectedErrors.add(1, { operation: 'cart-item', status: String(cart.status) });
    check(cart, { 'cart item added': (response) => response.status === 200 });
    return;
  }

  const started = Date.now();
  const checkout = http.post(`${BASE_URL}/api/buyers/${encodeURIComponent(buyerRef)}/cart/checkout`,
    JSON.stringify({ sellerCoupons: [{ sellerId: seed.sellerId, code: seed.couponCode }] }), {
      headers: { ...headers, 'Idempotency-Key': idempotencyKey },
      tags: { phase: 'measure', operation: 'checkout', mode: MODE },
    });
  measuredHttpRequests.add(1, { mode: MODE, operation: 'checkout' });
  checkoutHttpLatency.add(checkout.timings.duration, { mode: MODE });

  if (checkout.status === 409 && MODE === 'limited') {
    capConflictLatency.add(checkout.timings.duration, { mode: MODE });
    conflicts.add(1, { mode: MODE });
    check(checkout, { 'limited coupon rejects after capacity is reserved': (response) => response.status === 409 });
    return;
  }
  if (checkout.status !== 200) {
    unexpectedErrors.add(1, { operation: 'checkout', status: String(checkout.status) });
    check(checkout, { 'checkout accepted or cap reached': (response) => response.status === 200 });
    return;
  }

  winners.add(1, { mode: MODE });
  acceptedCheckoutLatency.add(checkout.timings.duration, { mode: MODE });
  const body = checkout.json();
  const shapeValid = check(body, {
    'checkout has one SellerOrder and net total': (value) => Boolean(value.checkoutId)
      && Array.isArray(value.orders) && value.orders.length === 1
      && value.totalWon === UNIT_PRICE_WON - DISCOUNT_WON,
  });
  if (!shapeValid) {
    unexpectedErrors.add(1, { operation: 'checkout-shape', status: 'invalid' });
    return;
  }

  const payment = http.post(`${BASE_URL}/api/checkouts/${body.checkoutId}/payment`, null, {
    tags: { phase: 'measure', operation: 'payment', mode: MODE },
  });
  measuredHttpRequests.add(1, { mode: MODE, operation: 'payment' });
  if (payment.status === 200 && payment.json().paymentState === 'CAPTURED') {
    captures.add(1, { mode: MODE });
  } else {
    unexpectedErrors.add(1, { operation: 'payment', status: String(payment.status) });
    check(payment, { 'winning checkout captured': (response) => response.status === 200
      && response.json().paymentState === 'CAPTURED' });
  }
  acceptedSessionLatency.add(Date.now() - started, { mode: MODE });
}

export function handleSummary(data) {
  const value = (name, key) => data.metrics[name] ? data.metrics[name].values[key] : null;
  const result = {
    runId: RUN_ID,
    vus: VUS,
    iterationsPerVu: ITERATIONS_PER_VU,
    mode: MODE,
    attempts: value('coupon_checkout_attempts', 'count'),
    attemptsPerSecond: value('coupon_checkout_attempts', 'rate'),
    measuredHttpRequests: value('coupon_measured_http_requests', 'count'),
    measuredHttpRps: value('coupon_measured_http_requests', 'rate'),
    winners: value('coupon_checkout_winners', 'count'),
    capConflicts: value('coupon_capacity_conflicts', 'count'),
    captures: value('coupon_checkout_captures', 'count'),
    unexpectedErrors: value('coupon_test_unexpected_errors', 'count'),
    checkoutHttpP95Ms: value('coupon_checkout_http_latency', 'p(95)'),
    acceptedCheckoutP95Ms: value('coupon_accepted_checkout_latency', 'p(95)'),
    capConflictP95Ms: value('coupon_cap_conflict_latency', 'p(95)'),
    acceptedSessionP95Ms: value('coupon_accepted_session_latency', 'p(95)'),
  };
  return { stdout: `${JSON.stringify(result, null, 2)}\n` };
}
