import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { registerBuyer, loginBuyer, authHeaders } from './lib/auth.js';

// Realistic mixed read/write journey for the ATLAS catalog and cart (EXP-037).
//
// Why this replaces a pure read loop
// ---------------------------------
// `catalog-read-capacity.js` issues six GETs per iteration, so it measures the
// catalogue read path and nothing else. A real storefront is dominated by reads but
// its cost and failure modes come from the minority of writes: cart mutation,
// checkout reservation, and the read-after-write paths that follow them. This script
// keeps the read majority and mixes in the write minority at published funnel rates.
//
// Ratios (published benchmarks, not invented):
//   - add-to-cart: 6.04% of sessions (Dynamic Yield rolling 12-month benchmark,
//     reported 2026). Rounded to 6%.
//   - checkout completion: ~1.5-2% of sessions in the same benchmark family.
// Both are far below 1:1, so writes must be a small fraction of traffic; a scenario
// that adds to cart on every iteration would misrepresent the system.
//
// Resume behaviour: a returning shopper's cart survives between sessions. The
// script therefore re-reads an existing cart (resume) before mutating it, and
// verifies the mutation is visible afterwards. That read-after-write check is the
// part a pure read scenario cannot exercise.
//
// Skew: `SEEDED_SELLER_IDS` and `HOT_SELLER_SHARE` concentrate a share of traffic on
// one seller, because real catalogs and promotions are not uniformly distributed.
// Uniform-only load hides hot-key and lock-contention behaviour.
//
// Environment:
//   BASE_URL                  API root (e.g. http://application:8080)
//   SESSION_RATE_NUMERATOR / SESSION_RATE_TIME_UNIT   arrival rate of "sessions"
//   FIXED_VUS, DURATION, SUMMARY_FILE
//   SEEDED_SELLER_IDS         comma-separated seller UUIDs from the seed
//   HOT_SELLER_SHARE          fraction of sessions pinned to the first seller (default 0)
//   CART_PROBABILITY          add-to-cart share (default 0.06)
//   CHECKOUT_PROBABILITY      checkout attempt share within cart adds (default 0.25)

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const RATE_NUMERATOR = Number(__ENV.SESSION_RATE_NUMERATOR || '');
const RATE_TIME_UNIT = __ENV.SESSION_RATE_TIME_UNIT || '1s';
const FIXED_VUS = Number(__ENV.FIXED_VUS || '');
const DURATION = __ENV.DURATION || '120s';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '';
const SELLER_IDS = (__ENV.SEEDED_SELLER_IDS || '').split(',').map((v) => v.trim()).filter(Boolean);
const HOT_SELLER_SHARE = Number(__ENV.HOT_SELLER_SHARE || '0');
const CART_PROBABILITY = Number(__ENV.CART_PROBABILITY || '0.06');
const CHECKOUT_PROBABILITY = Number(__ENV.CHECKOUT_PROBABILITY || '0.25');
const PAGE_LIMIT = 100;
const RUN_ID = (__ENV.RUN_ID || 'journey').replace(/[^A-Za-z0-9_-]/g, '-').slice(0, 32);

if (!BASE_URL) throw new Error('BASE_URL is required');
if (!Number.isSafeInteger(RATE_NUMERATOR) || RATE_NUMERATOR < 1) {
  throw new Error('SESSION_RATE_NUMERATOR must be a positive integer');
}
if (!Number.isSafeInteger(FIXED_VUS) || FIXED_VUS < 1) {
  throw new Error('FIXED_VUS must be a positive integer');
}
if (HOT_SELLER_SHARE < 0 || HOT_SELLER_SHARE > 1) {
  throw new Error('HOT_SELLER_SHARE must be between 0 and 1');
}

const ROUTE_LATENCY = {
  listing_first: new Trend('journey_listing_first_latency', true),
  listing_deep: new Trend('journey_listing_deep_latency', true),
  search_selective: new Trend('journey_search_selective_latency', true),
  product_detail: new Trend('journey_product_detail_latency', true),
  cart_read: new Trend('journey_cart_read_latency', true),
  cart_write: new Trend('journey_cart_write_latency', true),
  checkout: new Trend('journey_checkout_latency', true),
};
const CART_WRITES = new Counter('journey_cart_writes');
const CHECKOUT_ATTEMPTS = new Counter('journey_checkout_attempts');
const READ_AFTER_WRITE = new Rate('journey_cart_read_after_write_ok');
const DROPPED = new Counter('journey_cursor_missing');
const STATUS_CODES = new Counter('journey_error_status');
const UNEXPECTED_ERRORS = new Counter('journey_unexpected_errors');

export const options = {
  scenarios: {
    buyer_journey: {
      executor: 'constant-arrival-rate',
      rate: RATE_NUMERATOR,
      timeUnit: RATE_TIME_UNIT,
      duration: DURATION,
      preAllocatedVUs: FIXED_VUS,
      maxVUs: FIXED_VUS * 4,
      tags: { workload: 'buyer-journey' },
    },
  },
  thresholds: {
    checks: ['rate>0.995'],
    // Only shopper-phase requests count; setup() provisioning is excluded by tag.
    'http_req_failed{phase:measure}': ['rate<0.01'],
  },
};

function sellerFor(vu, iteration) {
  if (SELLER_IDS.length === 0) return null;
  // Deterministic skew: a fixed share of sessions always touch the hot seller.
  if (HOT_SELLER_SHARE > 0 && (iteration % 100) < HOT_SELLER_SHARE * 100) {
    return SELLER_IDS[0];
  }
  return SELLER_IDS[(vu + iteration) % SELLER_IDS.length];
}

function get(path, tag) {
  return http.get(`${BASE_URL}${path}`, {
    headers: { Accept: 'application/json' },
    tags: { name: tag, operation: tag, phase: 'measure' },
  });
}

function noteStatus(response, label) {
  if (response.status >= 400) {
    STATUS_CODES.add(1);
    let code = '';
    try {
      code = String(response.json().code || '');
    } catch (_) {
      code = '';
    }
    if (code) UNEXPECTED_ERRORS.add(1, { route: label, code });
  }
}

function timed(label, response) {
  noteStatus(response, label);
  ROUTE_LATENCY[label].add(response.timings.duration);
  return response;
}

// A generated buyer ref keeps carts isolated per virtual user without needing a
// signup round trip, which would dominate the measurement.
function buyerForVu(seed) {
  const account = seed.buyers[(__VU - 1) % seed.buyers.length];
  if (!account) throw new Error(`No authenticated buyer provisioned for VU ${__VU}`);
  return account;
}

// Cart endpoints require an authenticated buyer. Accounts are created once in
// setup() (signup + email verification + login), then reused per VU; creating an
// account per iteration would measure registration, not shopping.
export function setup() {
  const buyers = [];
  // Provision for the maximum VU count, not just the pre-allocated set: k6 may
  // scale up to maxVUs when the arrival rate cannot be met.
  const count = Math.max(FIXED_VUS * 4, 4);
  for (let index = 0; index < count; index += 1) {
    // Reuse accounts from a previous run in the same dataset: signup performs an
    // email round trip (Mailpit) which is slow and not what this scenario measures.
    // A 404 account makes signup the only option, so try login first.
    // Auth helpers tag their requests with phase=setup. Those requests are
    // provisioning, not shopper traffic, and a probe login that 401s before signup
    // must not be counted as a failed user request.
    try {
      buyers.push(loginBuyer(BASE_URL, RUN_ID, 'journey', index));
      continue;
    } catch (_) {
      buyers.push(registerBuyer(BASE_URL, RUN_ID, 'journey', index));
    }
  }
  return { buyers };
}

export default function (seed) {
  const iteration = __ITER;
  let sellerId = sellerFor(__VU, iteration);

  const first = timed('listing_first', get(`/api/products?limit=${PAGE_LIMIT}`, 'listing_first'));
  check(first, { 'listing: 200': (r) => r.status === 200 });

  let cursor = null;
  let firstProduct = null;
  try {
    const body = first.json();
    cursor = body.nextCursor;
    firstProduct = body.items?.[0]?.product ?? null;
  } catch (_) {
    cursor = null;
  }

  if (cursor) {
    const deep = timed('listing_deep',
      get(`/api/products?limit=${PAGE_LIMIT}&cursor=${encodeURIComponent(cursor)}`, 'listing_deep'));
    check(deep, { 'deep page: 200': (r) => r.status === 200 });
  } else {
    DROPPED.add(1);
  }

  // Selective search: the seeded catalog keeps exactly one `needle` hit per 100k
  // products, so this exercises the trigram path rather than a full scan.
  const search = timed('search_selective',
    get(`/api/products?query=needle&limit=${PAGE_LIMIT}`, 'search_selective'));
  check(search, { 'search: 200': (r) => r.status === 200 });

  let detailId = firstProduct?.productId ?? null;
  if (!detailId) {
    try {
      detailId = search.json().items?.[0]?.product?.productId ?? null;
    } catch (_) {
      detailId = null;
    }
  }
  if (detailId) {
    const detail = timed('product_detail', get(`/api/products/${detailId}`, 'product_detail'));
    check(detail, { 'detail: 200': (r) => r.status === 200 });
  }

  // Resume: a returning shopper re-reads the existing cart before touching it.
  const buyer = buyerForVu(seed);
  const buyerRef = buyer.buyerRef;
  const auth = authHeaders(buyer.accessToken, { Accept: 'application/json' });
  const cartRead = http.get(`${BASE_URL}/api/buyers/${buyerRef}/cart`, {
    headers: auth,
    // A shopper without a cart gets 404 CART_NOT_FOUND; that is a valid state.
    responseCallback: http.expectedStatuses(200, 404),
    tags: { name: 'cart_read', operation: 'cart_read', phase: 'measure' },
  });
  timed('cart_read', cartRead);
  // A first-time shopper has no cart yet; 404 CART_NOT_FOUND is a normal state, not
  // a failure. Anything else (401/403/5xx) is a real problem.
  check(cartRead, {
    'cart read: 200 or empty cart': (r) => r.status === 200 || r.status === 404,
  });

  // Write minority, at the published add-to-cart rate.
  if (!sellerId || Math.random() >= CART_PROBABILITY) return;

  let sku = null;
  if (cartRead.status === 200) {
    try {
      sku = cartRead.json()?.items?.[0]?.skuId ?? null;
    } catch (_) {
      sku = null;
    }
  }
  if (!sku) {
    // Fall back to the listing payload for a SKU on the chosen seller.
    try {
      const items = first.json().items ?? [];
      const match = items.find((i) => i.product.sellerId === sellerId) ?? items[0];
      sku = match?.variants?.[0]?.skuId ?? null;
      sellerId = match?.product?.sellerId ?? sellerId;
    } catch (_) {
      sku = null;
    }
  }
  if (!sku) return;

  const write = http.put(
    `${BASE_URL}/api/buyers/${buyerRef}/cart/items/${sellerId}/${sku}`,
    JSON.stringify({ quantity: 1 }),
    {
      headers: authHeaders(buyer.accessToken, { 'Content-Type': 'application/json' }),
      responseCallback: http.expectedStatuses(200, 409, 422),
      tags: { name: 'cart_write', operation: 'cart_write', phase: 'measure' },
    },
  );
  timed('cart_write', write);
  CART_WRITES.add(1);
  // Record the business code so a rejection is diagnosable from the summary alone.
  // Business rejections carry a `code`; assert the expected one explicitly so a
  // mismatch shows up as a named failing check instead of an opaque 4xx count.
  let writeCode = 'OK';
  if (write.status !== 200) {
    try {
      writeCode = String(write.json().code || `HTTP_${write.status}`);
    } catch (_) {
      writeCode = `HTTP_${write.status}`;
    }
  }
  check(write, {
    'cart write: 200': (r) => r.status === 200,
    'cart write: no unknown rejection code': () => writeCode === 'OK',
  });
  if (writeCode !== 'OK') {
    console.log(`cart-write-rejected code=${writeCode} seller=${sellerId} sku=${sku}`);
  }

  // Read-after-write: the cart must reflect the mutation immediately. This is the
  // property a cache or read replica would break, so it is measured explicitly.
  const afterWrite = http.get(`${BASE_URL}/api/buyers/${buyerRef}/cart`, {
    headers: auth,
    responseCallback: http.expectedStatuses(200, 404),
    tags: { name: 'cart_read_after_write', operation: 'cart_read_after_write', phase: 'measure' },
  });
  let visible = false;
  try {
    visible = afterWrite.status === 200
      && (afterWrite.json()?.items ?? []).some((i) => i.skuId === sku);
  } catch (_) {
    visible = false;
  }
  READ_AFTER_WRITE.add(visible);
  check(afterWrite, { 'cart reflects write': () => visible });

  if (Math.random() < CHECKOUT_PROBABILITY) {
    CHECKOUT_ATTEMPTS.add(1);
    const checkout = http.post(`${BASE_URL}/api/buyers/${buyerRef}/cart/checkout`, null, {
      headers: authHeaders(buyer.accessToken, { 'Content-Type': 'application/json' }),
      // Checkout answers 4xx when the cart is not payable; that is a business
      // outcome, not a transport failure.
      responseCallback: http.expectedStatuses({ min: 200, max: 499 }),
      tags: { name: 'checkout', operation: 'checkout', phase: 'business' },
    });
    timed('checkout', checkout);
    check(checkout, { 'checkout: 2xx or business rejection': (r) => r.status < 500 });
  }
}

export function handleSummary(data) {
  const output = JSON.stringify(data, null, 2);
  const destinations = { stdout: `${output}\n` };
  if (SUMMARY_FILE) destinations[SUMMARY_FILE] = `${output}\n`;
  return destinations;
}
