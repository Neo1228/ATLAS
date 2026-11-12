import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { authHeaders, buyerForVu, createSellerProfile, registerBuyer } from './lib/auth.js';

const profile = __ENV.GATE4_PROFILE || 'baseline';
const mode = __ENV.GATE4_MODE || 'load';
const paymentMode = __ENV.PAYMENT_MODE || 'checkout-only';
const runId = __ENV.GATE4_RUN_ID || '';
const baseUrl = (__ENV.ATLAS_BASE_URL || 'http://application:8080').replace(/\/$/, '');
const inventoryCapacity = paymentMode === 'paypal-sandbox-order-smoke'
  ? 1 : profile === 'queue-backlog' ? 167 : 1667;
const maxActiveCheckouts = paymentMode === 'paypal-sandbox-order-smoke'
  ? 1 : profile === 'queue-backlog' ? 1 : 1667;
const unitPriceWon = Number(__ENV.UNIT_PRICE_WON || '1000');
const ticketTtlSeconds = 7200;
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || '1');
const paypalOrderSmoke = paymentMode === 'paypal-sandbox-order-smoke';

if (!['baseline', 'queue-backlog'].includes(profile)) {
  throw new Error(`Unsupported GATE4_PROFILE: ${profile}`);
}
if (!['pilot', 'load'].includes(mode)) {
  throw new Error(`Unsupported GATE4_MODE: ${mode}`);
}
if (!['checkout-only', 'paypal-sandbox-order-smoke'].includes(paymentMode)) {
  throw new Error('PAYMENT_MODE must be checkout-only or paypal-sandbox-order-smoke');
}
if (!/^[A-Za-z0-9_-]{1,32}$/.test(runId)) {
  throw new Error('GATE4_RUN_ID must match [A-Za-z0-9_-]{1,32}');
}
if (!Number.isSafeInteger(unitPriceWon) || unitPriceWon < 1) {
  throw new Error('UNIT_PRICE_WON must be a positive integer');
}
if (!Number.isInteger(preAllocatedVUs) || preAllocatedVUs < 1) {
  throw new Error('PREALLOCATED_VUS must be a positive integer');
}
if (paypalOrderSmoke && profile !== 'baseline') {
  throw new Error('paypal-sandbox-order-smoke requires GATE4_PROFILE=baseline');
}

const startedIterations = new Counter('gate4_iterations_started');
const measuredHttpRequests = new Counter('gate4_measured_http_requests');
const createdTickets = new Counter('gate4_tickets_created');
const waitingTickets = new Counter('gate4_tickets_waiting');
const expectedBacklog = new Counter('gate4_checkout_conflict_expected');
const inventoryUnavailable = new Counter('gate4_inventory_unavailable');
const checkoutReservations = new Counter('gate4_checkout_reservations');
const paymentRequests = new Counter('gate4_payment_requests');
const paypalOrderSubmissions = new Counter('gate4_paypal_order_submissions');
const unexpectedResponses = new Counter('gate4_unexpected_responses');
const buyerSessionDuration = new Trend('gate4_buyer_session_duration', true);

const scenario = paypalOrderSmoke
  ? { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '2m' }
  : mode === 'pilot'
    ? { executor: 'constant-vus', vus: 1, duration: '60s' }
    : {
      executor: 'constant-arrival-rate',
      rate: 10000,
      timeUnit: '1h',
      duration: '10m',
      preAllocatedVUs,
      maxVUs: preAllocatedVUs,
    };

export const options = {
  scenarios: { buyer_flow: scenario },
  tags: { gate4_run: runId, gate4_profile: profile, gate4_mode: mode, payment_mode: paymentMode },
};

function postCreated(path, body, operation, accessToken) {
  const response = http.post(`${baseUrl}${path}`, JSON.stringify(body), {
    headers: authHeaders(accessToken, { 'Content-Type': 'application/json' }),
    tags: { phase: 'setup', stage: operation },
  });
  if (response.status !== 201) {
    throw new Error(`${operation} setup failed: HTTP ${response.status}`);
  }
  return response.json();
}

export function setup() {
  const sellerAccount = registerBuyer(baseUrl, runId, 'seller', 0);
  const sellerId = createSellerProfile(baseUrl, sellerAccount);
  const skuId = `gate4-${runId}`;
  const product = postCreated(`/api/sellers/${sellerId}/products`, {
    name: `Gate4 ${runId}`,
    description: `Synthetic Gate4 fixture ${runId}`,
  }, 'product', sellerAccount.accessToken);
  postCreated(`/api/sellers/${sellerId}/products/${product.productId}/skus`, {
    skuId,
    priceWon: unitPriceWon,
    initialStock: inventoryCapacity,
  }, 'sku', sellerAccount.accessToken);

  const policy = http.put(`${baseUrl}/api/sellers/${sellerId}/skus/${encodeURIComponent(skuId)}/admission-policy`,
    JSON.stringify({ maxActiveCheckouts }), {
      headers: authHeaders(sellerAccount.accessToken, { 'Content-Type': 'application/json' }),
      tags: { phase: 'setup', stage: 'admission-policy' },
    });
  if (policy.status !== 200) throw new Error(`Admission policy setup failed: HTTP ${policy.status}`);

  const campaign = postCreated('/api/campaigns', {
    sellerId,
    skuId,
    queueTicketTtlSeconds: ticketTtlSeconds,
  }, 'campaign', sellerAccount.accessToken);
  const opened = http.post(`${baseUrl}/api/campaigns/${campaign.campaignId}/open`, null, {
    headers: authHeaders(sellerAccount.accessToken),
    tags: { phase: 'setup', stage: 'campaign-open' },
  });
  if (opened.status !== 200) throw new Error(`Campaign open failed: HTTP ${opened.status}`);

  const buyerCount = paypalOrderSmoke ? 1 : mode === 'pilot' ? 1 : preAllocatedVUs;
  const buyers = Array.from({ length: buyerCount }, (_, index) =>
    registerBuyer(baseUrl, runId, 'buyer', index));
  console.log(`GATE4 fixture run=${runId} profile=${profile} campaign=${campaign.campaignId} `
    + `stock=${inventoryCapacity} activeLimit=${maxActiveCheckouts} buyers=${buyerCount}`);
  return { campaignId: campaign.campaignId, skuId, buyers };
}

export default function buyerFlow(data) {
  const startedAt = Date.now();
  startedIterations.add(1);
  const account = buyerForVu(baseUrl, data.buyers, __VU);
  const idempotencyKey = `${runId}-${__VU}-${__ITER}`;

  const joined = http.post(
    `${baseUrl}/api/campaigns/${data.campaignId}/tickets`,
    JSON.stringify({ skuId: data.skuId, buyerRef: account.buyerRef }),
    { headers: authHeaders(account.accessToken, { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey }),
      tags: { stage: 'queue-join', phase: 'measure' } },
  );
  measuredHttpRequests.add(1);
  if (joined.status !== 201) {
    unexpectedResponses.add(1, { stage: 'queue-join', status: String(joined.status) });
    buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'join-error' });
    check(joined, { 'queue join returns 201': (response) => response.status === 201 });
    return;
  }

  const ticket = joined.json();
  createdTickets.add(1);
  if (ticket.status === 'WAITING') waitingTickets.add(1);

  const checkout = http.post(
    `${baseUrl}/api/campaigns/${data.campaignId}/tickets/${ticket.ticketId}/checkout`,
    null,
    { headers: authHeaders(account.accessToken), tags: { stage: 'checkout', phase: 'measure' } },
  );
  measuredHttpRequests.add(1);
  if (checkout.status === 409) {
    if (profile === 'queue-backlog') {
      expectedBacklog.add(1);
      buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'backlog' });
    } else {
      unexpectedResponses.add(1, { stage: 'checkout', status: '409' });
      buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'unexpected-conflict' });
    }
    check(checkout, {
      'checkout 409 only occurs in queue-backlog profile': () => profile === 'queue-backlog',
    });
    return;
  }
  if (checkout.status !== 200) {
    unexpectedResponses.add(1, { stage: 'checkout', status: String(checkout.status) });
    buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'checkout-error' });
    check(checkout, { 'checkout returns 200 or expected 409': (response) => response.status === 200 });
    return;
  }

  const order = checkout.json();
  if (order.state === 'FAILED') {
    inventoryUnavailable.add(1);
    buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'inventory-unavailable' });
    return;
  }

  checkoutReservations.add(1);
  if (!paypalOrderSmoke) {
    buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'checkout-only' });
    return;
  }

  const payment = http.post(`${baseUrl}/api/orders/${order.orderId}/payment`, null, {
    headers: authHeaders(account.accessToken),
    tags: { stage: 'payment', phase: 'measure', provider: 'paypal-sandbox' },
  });
  measuredHttpRequests.add(1);
  paymentRequests.add(1);
  if (payment.status !== 200) {
    unexpectedResponses.add(1, { stage: 'payment', status: String(payment.status) });
    buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'payment-error' });
    check(payment, { 'PayPal Sandbox order submission returns 200': (response) => response.status === 200 });
    return;
  }

  const result = payment.json();
  if (result.paymentState === 'AUTHORIZATION_PENDING' && result.paymentApprovalUrl) {
    paypalOrderSubmissions.add(1);
    buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'paypal-order-submitted' });
    check(payment, { 'PayPal Sandbox order awaits Buyer approval': () => result.paymentState === 'AUTHORIZATION_PENDING'
        && typeof result.paymentApprovalUrl === 'string' && result.paymentApprovalUrl.length > 0 });
    return;
  }

  unexpectedResponses.add(1, { stage: 'payment', status: result.paymentState || 'unknown' });
  buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'unexpected-payment-state' });
  check(payment, { 'PayPal Sandbox order awaits Buyer approval': () => result.paymentState === 'AUTHORIZATION_PENDING'
        && typeof result.paymentApprovalUrl === 'string' && result.paymentApprovalUrl.length > 0 });
}

export function handleSummary(data) {
  const metricCount = (name) => data.metrics[name]?.values.count || 0;
  const metricRate = (name) => data.metrics[name]?.values.rate || 0;
  const latency = data.metrics.gate4_buyer_session_duration?.values || {};
  const summary = {
    runId,
    profile,
    mode,
    paymentMode,
    inventoryCapacity,
    maxActiveCheckouts,
    startedIterations: metricCount('gate4_iterations_started'),
    createdTickets: metricCount('gate4_tickets_created'),
    waitingTickets: metricCount('gate4_tickets_waiting'),
    expectedBacklog: metricCount('gate4_checkout_conflict_expected'),
    checkoutReservations: metricCount('gate4_checkout_reservations'),
    checkoutReservationTps: metricRate('gate4_checkout_reservations'),
    paymentRequests: metricCount('gate4_payment_requests'),
    paypalOrderSubmissions: metricCount('gate4_paypal_order_submissions'),
    paypalOrderSubmissionTps: metricRate('gate4_paypal_order_submissions'),
    inventoryUnavailable: metricCount('gate4_inventory_unavailable'),
    unexpectedResponses: metricCount('gate4_unexpected_responses'),
    requestRps: metricRate('gate4_measured_http_requests'),
    buyerSessionLatencyMs: {
      p50: latency.med,
      p95: latency['p(95)'],
      p99: latency['p(99)'],
    },
    droppedIterations: metricCount('dropped_iterations'),
  };
  const output = JSON.stringify(summary, null, 2);
  const destinations = { stdout: `${output}\n` };
  if (__ENV.SUMMARY_FILE) destinations[__ENV.SUMMARY_FILE] = `${output}\n`;
  return destinations;
}
