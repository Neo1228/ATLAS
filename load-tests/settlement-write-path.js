import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const rawBaseUrl = __ENV.BASE_URL;
const BASE_URL = rawBaseUrl ? rawBaseUrl.replace(/\/+$/, '') : '';
const RUN_ID = __ENV.RUN_ID;
const PHASE = __ENV.PHASE || 'measure';
const WRITE_COUNT = Number(__ENV.WRITE_COUNT || 100);
const UNIT_PRICE_WON = Number(__ENV.UNIT_PRICE_WON || 100);
const operationLatency = new Trend('exp022_operation_latency_ms');
const operationCount = new Counter('exp022_operation_count');

if (!BASE_URL) throw new Error('BASE_URL is required');
if (!/^https?:\/\//i.test(BASE_URL)) throw new Error('BASE_URL must be an HTTP(S) URL');
if (!/^[A-Za-z0-9_-]{1,32}$/.test(RUN_ID || '')) throw new Error('RUN_ID must match [A-Za-z0-9_-]{1,32}');
if (PHASE !== 'seed' && PHASE !== 'measure') throw new Error('PHASE must be seed or measure');
if (!Number.isSafeInteger(WRITE_COUNT) || WRITE_COUNT !== 100) throw new Error('WRITE_COUNT must be 100');
if (!Number.isSafeInteger(UNIT_PRICE_WON) || UNIT_PRICE_WON < 1) throw new Error('UNIT_PRICE_WON must be a positive integer');

export const options = {
  scenarios: {
    settlement_write: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: PHASE === 'seed' ? 1 : WRITE_COUNT,
      maxDuration: '5m',
    },
  },
};

function postCreated(path, body, operation) {
  const response = http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    tags: { phase: 'seed', operation },
  });
  if (response.status !== 201) fail(`POST ${path} expected HTTP 201, got ${response.status}`);
  let value;
  try {
    value = response.json();
  } catch (_) {
    fail(`POST ${path} returned invalid JSON`);
  }
  return value;
}

function seedFixture() {
  const seller = postCreated('/api/sellers', null, 'seller');
  if (!seller.sellerId) fail('Seller response missing sellerId');
  const product = postCreated(`/api/sellers/${seller.sellerId}/products`, {
    name: `exp022-${RUN_ID}`,
    description: `Synthetic EXP-022 ${RUN_ID}`,
  }, 'product');
  if (!product.productId) fail('Product response missing productId');
  const skuId = `exp022-${RUN_ID}`;
  const sku = postCreated(`/api/sellers/${seller.sellerId}/products/${product.productId}/skus`, {
    skuId,
    priceWon: UNIT_PRICE_WON,
    initialStock: WRITE_COUNT,
  }, 'sku');
  if (!sku.skuId) fail('SKU response missing skuId');
  const fixture = { runId: RUN_ID, sellerId: seller.sellerId, skuId: sku.skuId, priceWon: UNIT_PRICE_WON };
  console.log(`EXP022_FIXTURE=${JSON.stringify(fixture)}`);
  return fixture;
}

export function setup() {
  if (PHASE === 'seed') return seedFixture();
  if (!__ENV.SELLER_ID || !__ENV.SKU_ID) {
    throw new Error('SELLER_ID and SKU_ID from EXP022_FIXTURE are required for measure phase');
  }
  return { runId: RUN_ID, sellerId: __ENV.SELLER_ID, skuId: __ENV.SKU_ID, priceWon: UNIT_PRICE_WON };
}

function measureCheckout(seed) {
  const startedAt = Date.now();
  const iteration = exec.scenario.iterationInTest;
  const buyerRef = encodeURIComponent(`${RUN_ID}-buyer-${iteration}`);
  const idempotencyKey = `${RUN_ID}-checkout-${iteration}`;
  const item = http.put(
    `${BASE_URL}/api/buyers/${buyerRef}/cart/items/${seed.sellerId}/${encodeURIComponent(seed.skuId)}`,
    JSON.stringify({ quantity: 1 }),
    { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'measure', operation: 'cart-item' } },
  );
  if (!check(item, { 'cart item accepted': (r) => r.status >= 200 && r.status < 300 })) fail('Cart item request failed');

  const checkout = http.post(`${BASE_URL}/api/buyers/${buyerRef}/cart/checkout`, null, {
    headers: { 'Idempotency-Key': idempotencyKey },
    tags: { phase: 'measure', operation: 'checkout' },
  });
  if (!check(checkout, { 'checkout created': (r) => r.status >= 200 && r.status < 300 })) fail('Checkout request failed');
  let checkoutBody;
  try {
    checkoutBody = checkout.json();
  } catch (_) {
    fail('Checkout response was not valid JSON');
  }
  if (!check(checkoutBody, {
    'checkout contains one expected order': (body) => Boolean(body.checkoutId)
      && Array.isArray(body.orders) && body.orders.length === 1 && body.totalWon === seed.priceWon,
  })) fail('Checkout response shape or total was unexpected');

  const payment = http.post(`${BASE_URL}/api/checkouts/${checkoutBody.checkoutId}/payment`, null, {
    tags: { phase: 'measure', operation: 'payment' },
  });
  if (!check(payment, { 'payment succeeded': (r) => r.status >= 200 && r.status < 300 })) fail('Payment request failed');
  let paymentBody;
  try {
    paymentBody = payment.json();
  } catch (_) {
    fail('Payment response was not valid JSON');
  }
  if (!check(paymentBody, {
    'payment is CAPTURED with one order': (body) => body.paymentState === 'CAPTURED'
      && Array.isArray(body.orders) && body.orders.length === 1,
  })) fail('Payment response shape or state was unexpected');

  operationLatency.add(Date.now() - startedAt, { phase: 'measure' });
  operationCount.add(1, { phase: 'measure' });
}

export default function (seed) {
  if (PHASE === 'measure') measureCheckout(seed);
}
