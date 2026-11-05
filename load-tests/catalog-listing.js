import http from 'k6/http';
import { check } from 'k6';
import encoding from 'k6/encoding';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const SELLER_ID = __ENV.SELLER_ID || '';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'catalog-listing-summary.json';
const PAGE_LIMIT = 100;
const QUERIES = ['broad', 'needle'];

if (!BASE_URL) throw new Error('BASE_URL is required (local API URL only)');
if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(SELLER_ID)) {
  throw new Error('SELLER_ID must be the seeded seller UUID');
}

// One VU and one executor avoid concurrent scenarios; each listing shape gets 100 serial iterations.
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
    // Failed JSON parsing is reported by the response-shape check.
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
      const decoded = encoding.b64decode(payload.nextCursor, 'rawurl');
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

export default function () {
  const iteration = __ITER % 4;
  let label;
  let url;
  let expectedSeller = null;
  if (iteration === 0) {
    label = 'global';
    url = `${BASE_URL}/api/products?limit=${PAGE_LIMIT}`;
  } else if (iteration === 1) {
    label = 'seller';
    url = `${BASE_URL}/api/sellers/${SELLER_ID}/products?limit=${PAGE_LIMIT}`;
    expectedSeller = SELLER_ID;
  } else {
    const query = QUERIES[iteration - 2];
    label = `search_${query}`;
    url = `${BASE_URL}/api/products?query=${query}&limit=${PAGE_LIMIT}`;
  }

  const response = http.get(url, {
    headers: { Accept: 'application/json' },
    tags: { name: `catalog_listing_${label}`, listing_scenario: label },
  });
  listingCheck(response, label, expectedSeller);
}

export function handleSummary(data) {
  return { [SUMMARY_FILE]: JSON.stringify(data, null, 2) };
}
