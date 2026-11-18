import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { registerBuyer, loginBuyer, authHeaders } from './lib/auth.js';

// Full-domain buyer journey for ATLAS (EXP-038).
//
// Why another scenario
// --------------------
// `catalog-read-capacity.js` covers the read path only. `buyer-journey-capacity.js`
// adds cart writes and read-after-write, but still stops before payment and never
// touches fulfilment or settlement. A storefront's real cost sits in the minority of
// sessions that reach checkout, because those hit inventory reservations, order
// creation, payment state transitions, and settlement rows.
//
// This scenario walks the whole slice that the platform actually implements:
//   browse -> search -> detail -> cart -> checkout -> order payment -> shipment ->
//   settlement statement read
//
// Every step is conditional on the previous one succeeding, exactly like a real
// shopper, so the write path is exercised at realistic (low) rates instead of being
// mixed in at 1:1. The funnel defaults follow published benchmarks:
//   - add to cart: 6% of sessions
//   - complete checkout: ~1.5-2% of sessions (so 25% of cart adds)
//
// Payment note: the local stack runs with `ATLAS_PAYMENT_PROVIDER=disabled`, so
// `/orders/{id}/payment` records a deterministic local outcome rather than calling
// PayPal. That still exercises the state machine, inventory claim, and settlement
// write path this experiment measures. It is NOT a measurement of PayPal latency;
// that is stated as a limitation in the experiment document.
//
// Environment:
//   BASE_URL, SESSION_RATE_NUMERATOR, SESSION_RATE_TIME_UNIT, FIXED_VUS, DURATION,
//   SUMMARY_FILE, RUN_ID, SEEDED_SELLER_IDS, HOT_SELLER_SHARE,
//   CART_PROBABILITY (default 0.06), CHECKOUT_SHARE (default 0.25),
//   SHIP_SHARE (default 0.5), STATEMENT_SHARE (default 0.2)

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const RATE_NUMERATOR = Number(__ENV.SESSION_RATE_NUMERATOR || '');
const RATE_TIME_UNIT = __ENV.SESSION_RATE_TIME_UNIT || '1s';
const FIXED_VUS = Number(__ENV.FIXED_VUS || '');
const DURATION = __ENV.DURATION || '120s';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '';
const RUN_ID = (__ENV.RUN_ID || 'fulldomain').replace(/[^A-Za-z0-9_-]/g, '-').slice(0, 32);
const SELLER_IDS = (__ENV.SEEDED_SELLER_IDS || '').split(',').map((v) => v.trim()).filter(Boolean);
const HOT_SELLER_SHARE = Number(__ENV.HOT_SELLER_SHARE || '0');
const CART_PROBABILITY = Number(__ENV.CART_PROBABILITY || '0.06');
const CHECKOUT_SHARE = Number(__ENV.CHECKOUT_SHARE || '0.25');
const SHIP_SHARE = Number(__ENV.SHIP_SHARE || '0.5');
const STATEMENT_SHARE = Number(__ENV.STATEMENT_SHARE || '0.2');
const PAGE_LIMIT = 100;

if (!BASE_URL) throw new Error('BASE_URL is required');
if (!Number.isSafeInteger(RATE_NUMERATOR) || RATE_NUMERATOR < 1) {
  throw new Error('SESSION_RATE_NUMERATOR must be a positive integer');
}
if (!Number.isSafeInteger(FIXED_VUS) || FIXED_VUS < 1) {
  throw new Error('FIXED_VUS must be a positive integer');
}

const LATENCY = {
  listing: new Trend('fd_listing_latency', true),
  search: new Trend('fd_search_latency', true),
  detail: new Trend('fd_detail_latency', true),
  cart_read: new Trend('fd_cart_read_latency', true),
  cart_write: new Trend('fd_cart_write_latency', true),
  checkout: new Trend('fd_checkout_latency', true),
  payment: new Trend('fd_payment_latency', true),
  shipment: new Trend('fd_shipment_latency', true),
  statement: new Trend('fd_statement_latency', true),
};
const ORDERS_CREATED = new Counter('fd_orders_created');
const PAYMENTS_SUBMITTED = new Counter('fd_payments_submitted');
const SHIPMENTS_CREATED = new Counter('fd_shipments_created');
const STATEMENTS_READ = new Counter('fd_statements_read');
// Diagnoses whether checkout returned orders at all, instead of silently skipping.
const CHECKOUT_CODES = new Counter('fd_checkout_rejections');
const ORDER_WITH_ORDERS = new Counter('fd_checkout_with_orders');
const ORDER_WITHOUT_ORDERS = new Counter('fd_checkout_without_orders');
const READ_AFTER_WRITE = new Rate('fd_cart_read_after_write_ok');

export const options = {
  scenarios: {
    full_domain: {
      executor: 'constant-arrival-rate',
      rate: RATE_NUMERATOR,
      timeUnit: RATE_TIME_UNIT,
      duration: DURATION,
      preAllocatedVUs: FIXED_VUS,
      maxVUs: FIXED_VUS * 4,
      tags: { workload: 'full-domain' },
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    'http_req_failed{phase:measure}': ['rate<0.02'],
  },
};

function sellerFor(vu, iteration) {
  if (SELLER_IDS.length === 0) return null;
  if (HOT_SELLER_SHARE > 0 && (iteration % 100) < HOT_SELLER_SHARE * 100) return SELLER_IDS[0];
  return SELLER_IDS[(vu + iteration) % SELLER_IDS.length];
}

function getJson(path, tag, headers) {
  return http.get(`${BASE_URL}${path}`, {
    headers: headers || { Accept: 'application/json' },
    responseCallback: http.expectedStatuses(200, 404),
    tags: { name: tag, operation: tag, phase: 'measure' },
  });
}

function timed(label, response) {
  LATENCY[label].add(response.timings.duration);
  return response;
}

export function setup() {
  const buyers = [];
  const count = Math.min(Math.max(FIXED_VUS, 4), 48);
  for (let index = 0; index < count; index += 1) {
    // Reuse accounts across runs; signup does a mail round trip and is not measured.
    try {
      buyers.push(loginBuyer(BASE_URL, RUN_ID, 'fulldomain', index));
    } catch (_) {
      buyers.push(registerBuyer(BASE_URL, RUN_ID, 'fulldomain', index));
    }
  }
  return { buyers };
}

export default function (seed) {
  const buyer = seed.buyers[(__VU - 1) % seed.buyers.length];
  if (!buyer) throw new Error(`No authenticated buyer for VU ${__VU}`);
  const auth = authHeaders(buyer.accessToken, { Accept: 'application/json' });
  const jsonAuth = authHeaders(buyer.accessToken, { 'Content-Type': 'application/json' });
  const sellerId = sellerFor(__VU, __ITER);

  // 1. Browse
  const listing = timed('listing', getJson(`/api/products?limit=${PAGE_LIMIT}`, 'listing'));
  check(listing, { 'listing: 200': (r) => r.status === 200 });
  let product = null;
  try {
    product = listing.json().items?.[0]?.product ?? null;
  } catch (_) {
    product = null;
  }

  // 2. Search (selective: one `needle` product per 100k rows)
  const search = timed('search', getJson('/api/products?query=needle&limit=20', 'search'));
  check(search, { 'search: 200': (r) => r.status === 200 });

  // 3. Detail
  if (product?.productId) {
    const detail = timed('detail', getJson(`/api/products/${product.productId}`, 'detail'));
    check(detail, { 'detail: 200': (r) => r.status === 200 });
  }

  // 4. Resume cart
  const cart = timed('cart_read', getJson(`/api/buyers/${buyer.buyerRef}/cart`, 'cart_read', auth));
  check(cart, { 'cart: 200 or empty': (r) => r.status === 200 || r.status === 404 });

  // 5. Add to cart at the published rate
  if (!sellerId || Math.random() >= CART_PROBABILITY) return;
  let sku = null;
  let itemSeller = sellerId;
  try {
    const items = listing.json().items ?? [];
    const match = items.find((i) => i.product.sellerId === sellerId) ?? items[0];
    sku = match?.variants?.[0]?.skuId ?? null;
    itemSeller = match?.product?.sellerId ?? sellerId;
  } catch (_) {
    sku = null;
  }
  if (!sku) return;

  const write = http.put(
    `${BASE_URL}/api/buyers/${buyer.buyerRef}/cart/items/${itemSeller}/${sku}`,
    JSON.stringify({ quantity: 1 }),
    {
      headers: jsonAuth,
      responseCallback: http.expectedStatuses(200, 409, 422),
      tags: { name: 'cart_write', operation: 'cart_write', phase: 'measure' },
    },
  );
  timed('cart_write', write);
  check(write, { 'cart write: 200': (r) => r.status === 200 });

  const afterWrite = getJson(`/api/buyers/${buyer.buyerRef}/cart`, 'cart_after_write', auth);
  let visible = false;
  try {
    visible = afterWrite.status === 200
      && (afterWrite.json()?.items ?? []).some((i) => i.skuId === sku);
  } catch (_) {
    visible = false;
  }
  READ_AFTER_WRITE.add(visible);

  // 6. Checkout (order creation + inventory reservation)
  if (Math.random() >= CHECKOUT_SHARE) return;
  const idempotencyKey = `fd-${RUN_ID}-${__VU}-${__ITER}-${Date.now()}`;
  const checkout = http.post(`${BASE_URL}/api/buyers/${buyer.buyerRef}/cart/checkout`, null, {
    headers: authHeaders(buyer.accessToken, { 'Idempotency-Key': idempotencyKey }),
    responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
    tags: { name: 'checkout', operation: 'checkout', phase: 'measure' },
  });
  timed('checkout', checkout);
  // Record the business code itself so a rejection is named, not just counted.
  let checkoutCode = 'OK';
  if (checkout.status >= 300) {
    try {
      checkoutCode = String(checkout.json().code || `HTTP_${checkout.status}`);
    } catch (_) {
      checkoutCode = `HTTP_${checkout.status}`;
    }
  }
  check(checkout, {
    'checkout: 2xx': () => checkoutCode === 'OK',
    'checkout code recorded': () => checkoutCode !== '',
  });
  if (checkoutCode !== 'OK') {
    CHECKOUT_CODES.add(1);
    check(checkout, { [`checkout rejected: ${checkoutCode}`]: () => false });
  }
  // Any 3xx/4xx means the cart was not converted to orders; nothing downstream runs.
  if (checkout.status >= 300) return;
  let orderIds = [];
  let paymentAttemptId = null;
  try {
    const body = checkout.json();
    const orders = body.orders ?? [];
    orderIds = orders.map((o) => o.orderId).filter(Boolean);
    paymentAttemptId = body.paymentAttemptId ?? null;
  } catch (_) {
    orderIds = [];
  }
  // Two separate untagged counters: k6 only exposes tagged counters as submetrics,
  // which the JSON summary omits, so a tagged version would look like "never ran".
  if (orderIds.length > 0) ORDER_WITH_ORDERS.add(1); else ORDER_WITHOUT_ORDERS.add(1);
  if (orderIds.length === 0) return;
  ORDERS_CREATED.add(orderIds.length);

  // 7. Payment (local provider records state; does not call PayPal in this stack)
  for (const orderId of orderIds) {
    const payment = http.post(`${BASE_URL}/api/orders/${orderId}/payment`, null, {
      headers: jsonAuth,
      responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
      tags: { name: 'payment', operation: 'payment', phase: 'measure' },
    });
    timed('payment', payment);
    PAYMENTS_SUBMITTED.add(1);
    check(payment, { 'payment: <500': (r) => r.status < 500 });
  }

  // 8. Fulfilment: seller ships a paid order
  if (Math.random() >= SHIP_SHARE) return;
  const shipOrder = orderIds[0];
  const shipment = http.post(
    `${BASE_URL}/api/sellers/${itemSeller}/orders/${shipOrder}/shipments`,
    JSON.stringify({ carrier: 'CJ', trackingNumber: `TRK-${RUN_ID}-${__VU}-${__ITER}` }),
    {
      headers: jsonAuth,
      responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
      tags: { name: 'shipment', operation: 'shipment', phase: 'measure' },
    },
  );
  timed('shipment', shipment);
  if (shipment.status < 300) SHIPMENTS_CREATED.add(1);
  check(shipment, { 'shipment: <500': (r) => r.status < 500 });

  // 9. Settlement statement read (seller view of the ledger)
  if (Math.random() >= STATEMENT_SHARE) return;
  // Settlement statement is a seller-owned resource: the buyer token cannot read it.
  // This step therefore measures the authorization path, not the statement query, and
  // 403 is the expected outcome for a buyer principal.
  const statement = http.get(`${BASE_URL}/api/sellers/${itemSeller}/settlement-statement`, {
    headers: jsonAuth,
    responseCallback: http.expectedStatuses(200, 403, 404),
    tags: { name: 'statement', operation: 'statement', phase: 'measure' },
  });
  timed('statement', statement);
  if (statement.status === 200) STATEMENTS_READ.add(1);
  check(statement, {
    'statement: 200, forbidden, or none': (r) => [200, 403, 404].includes(r.status),
  });
}

export function handleSummary(data) {
  const output = JSON.stringify(data, null, 2);
  const destinations = { stdout: `${output}\n` };
  if (SUMMARY_FILE) destinations[SUMMARY_FILE] = `${output}\n`;
  return destinations;
}
