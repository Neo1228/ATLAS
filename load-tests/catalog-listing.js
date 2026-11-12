import http from 'k6/http';
import { check } from 'k6';
import encoding from 'k6/encoding';
import { Trend } from 'k6/metrics';
import { authHeaders, buyerForVu, createSellerProfile, registerBuyer } from './lib/auth.js';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const RUN_ID = __ENV.RUN_ID || '';
const UNIT_PRICE_WON = Number(__ENV.UNIT_PRICE_WON || '100');
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '';
const PAGE_LIMIT = 100;
const QUERIES = ['broad', 'needle'];

const SCENARIO_LATENCY = {
  global: new Trend('catalog_listing_global_latency', true),
  seller: new Trend('catalog_listing_seller_latency', true),
  search_broad: new Trend('catalog_listing_search_broad_latency', true),
  search_needle: new Trend('catalog_listing_search_needle_latency', true),
};

if (!BASE_URL) throw new Error('BASE_URL is required (local API URL only)');
if (!/^[A-Za-z0-9_-]{1,32}$/.test(RUN_ID)) throw new Error('RUN_ID must match [A-Za-z0-9_-]{1,32}');
if (!Number.isSafeInteger(UNIT_PRICE_WON) || UNIT_PRICE_WON < 1) {
  throw new Error('UNIT_PRICE_WON must be a positive integer');
}

// VU 1개가 네 목록 요청 형상을 직렬 실행.
export const options = {
  scenarios: {
    catalog_listing: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 400,
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
};

function postCreated(path, body, accessToken, operation) {
  const response = http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), {
    headers: authHeaders(accessToken, { 'Content-Type': 'application/json' }),
    tags: { phase: 'setup', operation },
  });
  if (response.status !== 201) throw new Error(`setup ${operation} failed: HTTP ${response.status}`);
  return response.json();
}

export function setup() {
  const sellerAccount = registerBuyer(BASE_URL, RUN_ID, 'seller', 0);
  const sellerId = createSellerProfile(BASE_URL, sellerAccount);
  const product = postCreated(`/api/sellers/${sellerId}/products`, {
    name: `catalog-${RUN_ID}`,
    description: `Synthetic catalog listing ${RUN_ID}`,
  }, sellerAccount.accessToken, 'product');
  const skuIds = [`catalog-${RUN_ID}-a`, `catalog-${RUN_ID}-b`];
  for (const skuId of skuIds) {
    postCreated(`/api/sellers/${sellerId}/products/${product.productId}/skus`, {
      skuId,
      priceWon: UNIT_PRICE_WON,
      initialStock: 1,
    }, sellerAccount.accessToken, 'sku');
  }
  console.log(`catalog fixture run=${RUN_ID} seller=${sellerId} product=${product.productId}`);
  return { sellerId, sellerAccounts: [sellerAccount] };
}

function uuidSortKey(uuid) {
  const bytes = uuid.replaceAll('-', '').match(/../g).map((part) => Number.parseInt(part, 16));
  bytes[0] &= 0x80;
  bytes[0] ^= 0x80;
  bytes[8] ^= 0x80;
}

function listingCheck(response, label, expectedSeller = null) {
  let payload = null;
  try {
    payload = response.json();
  } catch (_) {
    // JSON 파싱 실패는 아래 응답 형태 검사에서 보고.
  }

  const shapeValid = payload !== null && Array.isArray(payload.items)
    && (payload.nextCursor === null || typeof payload.nextCursor === 'string');
  const items = shapeValid ? payload.items : [];
  const itemValid = items.every((item) => item && item.product
    && typeof item.product.productId === 'string'
    && typeof item.product.sellerId === 'string'
    && typeof item.product.name === 'string'
    && Array.isArray(item.variants) && item.variants.length === 2);
  const expectedSellerValid = expectedSeller === null
    || items.every((item) => item.product.sellerId.toLowerCase() === expectedSeller.toLowerCase());
  const ordered = itemValid && items.every((item, index) => index === 0
    || `${uuidSortKey(items[index - 1].product.sellerId)}${uuidSortKey(items[index - 1].product.productId)}`
      <= `${uuidSortKey(item.product.sellerId)}${uuidSortKey(item.product.productId)}`);

  let cursorValid = payload !== null;
  if (cursorValid && payload.nextCursor !== null) {
    try {
      const decoded = String.fromCharCode(...new Uint8Array(encoding.b64decode(payload.nextCursor, 'rawurl')));
      const [cursorSeller, cursorProduct, ...extra] = decoded.split(':');
      const last = items[items.length - 1];
      cursorValid = extra.length === 0 && last !== undefined
        && cursorSeller === last.product.sellerId && cursorProduct === last.product.productId;
    } catch (_) {
      cursorValid = false;
    }
  }

  check(response, {
    [`${label}: HTTP 200`]: (res) => res.status === 200,
    [`${label}: {items,nextCursor} shape`]: () => shapeValid,
    [`${label}: product/two-SKU shape`]: () => itemValid,
    [`${label}: seller scope`]: () => expectedSellerValid,
    [`${label}: normalized seller/product order`]: () => ordered,
    [`${label}: cursor encodes final item`]: () => cursorValid,
    [`${label}: page size <= ${PAGE_LIMIT}`]: () => items.length <= PAGE_LIMIT,
  });
}

export default function (seed) {
  const iteration = __ITER % 4;
  let label;
  let url;
  let expectedSeller = null;
  if (iteration === 0) {
    label = 'global';
    url = `${BASE_URL}/api/products?limit=${PAGE_LIMIT}`;
  } else if (iteration === 1) {
    label = 'seller';
    url = `${BASE_URL}/api/sellers/${seed.sellerId}/products?limit=${PAGE_LIMIT}`;
    expectedSeller = seed.sellerId;
  } else {
    const query = QUERIES[iteration - 2];
    label = `search_${query}`;
    url = `${BASE_URL}/api/products?query=${query}&limit=${PAGE_LIMIT}`;
  }

  const headers = label === 'seller'
    ? authHeaders(buyerForVu(BASE_URL, seed.sellerAccounts, __VU).accessToken, { Accept: 'application/json' })
    : { Accept: 'application/json' };
  const response = http.get(url, {
    headers,
    tags: { name: `catalog_listing_${label}`, listing_scenario: label, phase: 'measure' },
  });
  listingCheck(response, label, expectedSeller);
  SCENARIO_LATENCY[label].add(response.timings.duration);
}

export function handleSummary(data) {
  const output = JSON.stringify(data, null, 2);
  const destinations = { stdout: `${output}\n` };
  if (SUMMARY_FILE) destinations[SUMMARY_FILE] = `${output}\n`;
  return destinations;
}
