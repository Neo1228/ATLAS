import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { authHeaders, buyerForVu, createSellerProfile, loginBuyer, registerBuyer } from './lib/auth.js';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const PROFILE = __ENV.PROFILE || '';
const RUN_ID = __ENV.RUN_ID || '';
const UNIT_PRICE_WON = Number(__ENV.UNIT_PRICE_WON || '');
const DURATION = __ENV.DURATION || '120s';
const SESSION_RATE_NUMERATOR = Number(__ENV.SESSION_RATE_NUMERATOR || '278');
const SESSION_RATE_TIME_UNIT = __ENV.SESSION_RATE_TIME_UNIT || '100s';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '';
const FIXED_VUS = Number(__ENV.FIXED_VUS || '');
const PAYMENT_MODE = __ENV.PAYMENT_MODE || 'checkout-only';

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
if (!['checkout-only', 'paypal-sandbox-order-smoke', 'paypal-sandbox-verify'].includes(PAYMENT_MODE)) {
  throw new Error('Unsupported PAYMENT_MODE');
}
if (PAYMENT_MODE !== 'checkout-only' && PROFILE !== 'one-seller') {
  throw new Error('PayPal Sandbox modes require PROFILE=one-seller');
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
const plannedSessions = Math.ceil((SESSION_RATE_NUMERATOR / sessionTimeUnitSeconds) * durationSeconds);
// 시작·종료 경계에서 추가 iteration이 발생해도 품절되지 않도록 1개 여유 수량 확보.
const initialStock = plannedSessions + 1;
if (!Number.isSafeInteger(initialStock) || initialStock < 1 || initialStock > 2147483647) {
  throw new Error('derived initialStock is outside the positive integer range supported by the API');
}

const EXPECTED_ORDER_COUNT = PROFILE === 'one-seller' ? 1 : 2;
const measuredHttpRequests = new Counter('measured_http_requests');
const sessionLatency = new Trend('checkout_session_duration', true);
const checkoutSessions = new Counter('completed_checkout_sessions');
const paypalOrderSubmissions = new Counter('paypal_sandbox_order_submissions');
const sessionErrors = new Counter('checkout_session_errors');
const sessionSamples = new Counter('checkout_session_samples');
const paypalVerificationSuccesses = new Counter('paypal_sandbox_verification_successes');

const scenario = ['paypal-sandbox-order-smoke', 'paypal-sandbox-verify'].includes(PAYMENT_MODE)
  ? {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    maxDuration: '5m',
    exec: 'checkoutSession',
    tags: { workload: 'buyer-checkout', profile: PROFILE, paymentMode: PAYMENT_MODE },
  }
  : {
    executor: 'constant-arrival-rate',
    rate: SESSION_RATE_NUMERATOR,
    timeUnit: SESSION_RATE_TIME_UNIT,
    duration: DURATION,
    preAllocatedVUs: FIXED_VUS,
    maxVUs: FIXED_VUS,
    exec: 'checkoutSession',
    tags: { workload: 'buyer-checkout', profile: PROFILE, paymentMode: PAYMENT_MODE },
  };

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: { checkout_sessions: scenario },
};

function postCreated(path, body, operation, accessToken) {
  const response = http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), {
    headers: authHeaders(accessToken, { 'Content-Type': 'application/json' }),
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

function createSellerProductSku(role, sellerAccount, existingSellerId = null) {
  const sellerId = existingSellerId || createSellerProfile(BASE_URL, sellerAccount);
  const productName = `exp016-${RUN_ID}-${PROFILE}-${role}`;
  const product = postCreated(`/api/sellers/${sellerId}/products`, {
    name: productName,
    description: `Synthetic EXP-016 seed ${RUN_ID} ${PROFILE} ${role}`,
  }, 'seed-product', sellerAccount.accessToken);
  if (!product.productId) throw new Error('setup product response missing productId');
  const skuId = `exp016-${RUN_ID}-${PROFILE}-${role}`;
  const sku = postCreated(`/api/sellers/${sellerId}/products/${product.productId}/skus`, {
    skuId,
    priceWon: UNIT_PRICE_WON,
    initialStock,
  }, 'seed-sku', sellerAccount.accessToken);
  if (!sku.skuId) throw new Error('setup SKU response missing skuId');
  return { sellerId, skuId };
}

export function setup() {
  if (PAYMENT_MODE === 'paypal-sandbox-verify') {
    required(__ENV.PAYPAL_CHECKOUT_ID, 'PAYPAL_CHECKOUT_ID from the Sandbox approval run');
    const buyerAccount = loginBuyer(BASE_URL, RUN_ID, 'buyer', 0);
    return { checkoutId: __ENV.PAYPAL_CHECKOUT_ID, buyerAccount };
  }
  const sellerAccountA = registerBuyer(BASE_URL, RUN_ID, 'seller-a', 0);
  const lineA = createSellerProductSku('a', sellerAccountA);
  const sellerAccountB = PROFILE === 'one-seller'
    ? sellerAccountA
    : registerBuyer(BASE_URL, RUN_ID, 'seller-b', 0);
  const lineB = PROFILE === 'one-seller'
    ? createSellerProductSku('b', sellerAccountB, lineA.sellerId)
    : createSellerProductSku('b', sellerAccountB);
  const buyerCount = PAYMENT_MODE === 'paypal-sandbox-order-smoke' ? 1 : FIXED_VUS;
  const buyers = Array.from({ length: buyerCount }, (_, index) =>
    registerBuyer(BASE_URL, RUN_ID, 'buyer', index));
  const seed = {
    profile: PROFILE,
    paymentMode: PAYMENT_MODE,
    runId: RUN_ID,
    sellerAId: lineA.sellerId,
    sellerBId: lineB.sellerId,
    skuAId: lineA.skuId,
    skuBId: lineB.skuId,
    unitPriceWon: UNIT_PRICE_WON,
    initialStockPerSku: initialStock,
    plannedSessions,
    buyers,
  };
  console.log(`EXP-016 seed: ${JSON.stringify({ ...seed, buyers: buyers.length })}`);
  return seed;
}

function failed(reason, response = null) {
  sessionErrors.add(1, { profile: PROFILE, reason });
  if (__ENV.K6_DEBUG_ERRORS === 'true') {
    let errorCode = 'unavailable';
    if (response) {
      try { const body = response.json(); errorCode = body.code || body.errorCode || 'no-code'; } catch (_) {}
    }
    console.error(`checkout failure stage=${reason} status=${response ? response.status : 'invalid-response'} code=${errorCode}`);
  }
  return false;
}

function requestOk(response, label) {
  return check(response, { [`${label} HTTP 2xx`]: (r) => r.status >= 200 && r.status < 300 });
}

function countMeasuredRequest() {
  measuredHttpRequests.add(1, { profile: PROFILE });
}

function verifySandboxCheckout(seed) {
  const response = http.get(`${BASE_URL}/api/checkouts/${seed.checkoutId}`, {
    headers: authHeaders(seed.buyerAccount.accessToken, { Accept: 'application/json' }),
    tags: { operation: 'paypal-sandbox-verification', profile: PROFILE, phase: 'measure' },
  });
  countMeasuredRequest();
  if (!requestOk(response, 'PayPal Sandbox checkout verification')) {
    return failed('sandbox_verify_http', response);
  }

  let body;
  try {
    body = response.json();
  } catch (_) {
    return failed('sandbox_verify_json', response);
  }
  const captured = check(body, {
    'checkout payment is CAPTURED': (checkout) => checkout.state === 'PAID'
      && checkout.paymentState === 'CAPTURED',
    'Seller orders are paid and inventory is claimed': (checkout) => Array.isArray(checkout.orders)
      && checkout.orders.length === EXPECTED_ORDER_COUNT
      && checkout.orders.every((order) => order.state === 'PAID' && order.inventoryState === 'CLAIMED'),
  });
  if (!captured) return failed('sandbox_verify_state', response);
  paypalVerificationSuccesses.add(1, { profile: PROFILE });
}

export function checkoutSession(seed) {
  if (PAYMENT_MODE === 'paypal-sandbox-verify') {
    verifySandboxCheckout(seed);
    return;
  }
  sessionSamples.add(1, { profile: PROFILE });
  const started = Date.now();
  const iteration = exec.scenario.iterationInTest;
  const account = buyerForVu(BASE_URL, seed.buyers, __VU);
  const buyer = encodeURIComponent(account.buyerRef);
  const idempotencyKey = `${RUN_ID}-checkout-${iteration}`;
  const headers = authHeaders(account.accessToken, { 'Content-Type': 'application/json' });

  const cartA = http.put(
    `${BASE_URL}/api/buyers/${buyer}/cart/items/${seed.sellerAId}/${encodeURIComponent(seed.skuAId)}`,
    JSON.stringify({ quantity: 1 }), { headers, tags: { operation: 'cart-item-a', profile: PROFILE, phase: 'measure' } },
  );
  countMeasuredRequest();
  if (!requestOk(cartA, 'add SKU A')) return failed('cart_a', cartA);

  const cartB = http.put(
    `${BASE_URL}/api/buyers/${buyer}/cart/items/${seed.sellerBId}/${encodeURIComponent(seed.skuBId)}`,
    JSON.stringify({ quantity: 1 }), { headers, tags: { operation: 'cart-item-b', profile: PROFILE, phase: 'measure' } },
  );
  countMeasuredRequest();
  if (!requestOk(cartB, 'add SKU B')) return failed('cart_b', cartB);

  const checkout = http.post(
    `${BASE_URL}/api/buyers/${buyer}/cart/checkout`, null,
    { headers: authHeaders(account.accessToken, { 'Idempotency-Key': idempotencyKey }),
      tags: { operation: 'checkout', profile: PROFILE, phase: 'measure' } },
  );
  countMeasuredRequest();
  if (!requestOk(checkout, 'checkout')) return failed('checkout', checkout);

  let checkoutBody;
  try {
    checkoutBody = checkout.json();
  } catch (_) {
    return failed('checkout_json', checkout);
  }
  const checkoutShapeOk = check(checkoutBody, {
    'checkout has id and expected child orders': (body) => Boolean(body.checkoutId)
      && Array.isArray(body.orders) && body.orders.length === EXPECTED_ORDER_COUNT,
    'checkout total matches two equal-price lines': (body) => body.totalWon === 2 * seed.unitPriceWon,
  });
  if (!checkoutShapeOk) return failed('checkout_shape_or_total', checkout);

  checkoutSessions.add(1, { profile: PROFILE });
  if (PAYMENT_MODE === 'paypal-sandbox-order-smoke') {
    const payment = http.post(
      `${BASE_URL}/api/checkouts/${checkoutBody.checkoutId}/payment`, null,
      { headers: authHeaders(account.accessToken),
        tags: { operation: 'payment', profile: PROFILE, phase: 'measure', provider: 'paypal-sandbox' } },
    );
    countMeasuredRequest();
    if (!requestOk(payment, 'PayPal Sandbox payment')) return failed('payment', payment);

    let paymentBody;
    try {
      paymentBody = payment.json();
    } catch (_) {
      return failed('payment_json', payment);
    }
    const approvalPending = check(paymentBody, {
      'PayPal order awaits Buyer approval': (body) => body.paymentState === 'AUTHORIZATION_PENDING'
        && body.state === 'PAYMENT_PENDING'
        && typeof body.paymentApprovalUrl === 'string' && body.paymentApprovalUrl.length > 0,
      'payment response retains expected child-order count': (body) => Array.isArray(body.orders)
        && body.orders.length === EXPECTED_ORDER_COUNT,
    });
    if (!approvalPending) return failed('payment_state_or_orders', payment);
    if (__ENV.PAYPAL_SHOW_APPROVAL_URL === 'true') {
      console.log(`PAYPAL_SANDBOX_APPROVAL_URL=${paymentBody.paymentApprovalUrl}`);
      console.log(`PAYPAL_SANDBOX_CHECKOUT_ID=${checkoutBody.checkoutId}`);
    }
    paypalOrderSubmissions.add(1, { profile: PROFILE });
  }

  sessionLatency.add(Date.now() - started, { profile: PROFILE });
}

export function handleSummary(data) {
  const completedCheckoutSessions = data.metrics.completed_checkout_sessions
    ? data.metrics.completed_checkout_sessions.values.count : 0;
  const report = {
    profile: PROFILE,
    paymentMode: PAYMENT_MODE,
    runId: RUN_ID,
    duration: DURATION,
    plannedSessionArrivalRate: `${SESSION_RATE_NUMERATOR}/${SESSION_RATE_TIME_UNIT} sessions/s`,
    fixedVUs: FIXED_VUS,
    initialStockPerSku: initialStock,
    plannedSessions,
    httpRequestRps: data.metrics.measured_http_requests
      ? data.metrics.measured_http_requests.values.rate : null,
    completedCheckoutSessions,
    completedCheckoutSessionTps: completedCheckoutSessions / durationSeconds,
    paypalSandboxOrderSubmissions: data.metrics.paypal_sandbox_order_submissions
      ? data.metrics.paypal_sandbox_order_submissions.values.count : 0,
    paypalSandboxVerificationSuccesses: data.metrics.paypal_sandbox_verification_successes
      ? data.metrics.paypal_sandbox_verification_successes.values.count : 0,
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
  };
  const output = JSON.stringify(report, null, 2);
  const destinations = { stdout: `${output}\n` };
  if (SUMMARY_FILE) destinations[SUMMARY_FILE] = `${output}\n`;
  return destinations;
}
