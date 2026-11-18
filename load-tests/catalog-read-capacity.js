import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// Read-path capacity scenario for EXP-035.
//
// Unlike catalog-listing.js (one VU, sequential correctness checks), this script
// drives a fixed arrival rate so throughput, queueing and connection use can be
// measured. It is intentionally read-only: it creates no sellers, products or
// orders, so it can run against a pre-seeded dataset.
//
// Required environment:
//   BASE_URL        local API root, e.g. http://application:8080
//   SESSION_RATE_NUMERATOR / SESSION_RATE_TIME_UNIT  arrival rate of 4-request groups
//   FIXED_VUS       preallocated VUs
//   DURATION        scenario duration
//   SUMMARY_FILE    k6 JSON summary path
// Optional:

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const RATE_NUMERATOR = Number(__ENV.SESSION_RATE_NUMERATOR || '');
const RATE_TIME_UNIT = __ENV.SESSION_RATE_TIME_UNIT || '1s';
const FIXED_VUS = Number(__ENV.FIXED_VUS || '');
const DURATION = __ENV.DURATION || '120s';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || '';
const PAGE_LIMIT = 100;

if (!BASE_URL) throw new Error('BASE_URL is required');
if (!Number.isSafeInteger(RATE_NUMERATOR) || RATE_NUMERATOR < 1) {
  throw new Error('SESSION_RATE_NUMERATOR must be a positive integer');
}
if (!Number.isSafeInteger(FIXED_VUS) || FIXED_VUS < 1) {
  throw new Error('FIXED_VUS must be a positive integer');
}

const ROUTE_LATENCY = {
  global_first_page: new Trend('read_global_first_page_latency', true),
  global_deep_page: new Trend('read_global_deep_page_latency', true),
  price_filter: new Trend('read_price_filter_latency', true),
  needle_search: new Trend('read_needle_search_latency', true),
  broad_search: new Trend('read_broad_search_latency', true),
  product_detail: new Trend('read_product_detail_latency', true),
};
const CURSOR_MISSES = new Counter('read_cursor_missing');

export const options = {
  scenarios: {
    catalog_reads: {
      executor: 'constant-arrival-rate',
      rate: RATE_NUMERATOR,
      timeUnit: RATE_TIME_UNIT,
      duration: DURATION,
      preAllocatedVUs: FIXED_VUS,
      maxVUs: FIXED_VUS * 4,
      tags: { workload: 'catalog-reads' },
    },
  },
  thresholds: {
    checks: ['rate>0.999'],
    http_req_failed: ['rate<0.001'],
  },
};

function getJson(path, tag) {
  const response = http.get(`${BASE_URL}${path}`, {
    headers: { Accept: 'application/json' },
    tags: { name: tag, operation: tag },
  });
  return response;
}

function record(label, response) {
  ROUTE_LATENCY[label].add(response.timings.duration);
  check(response, {
    [`${label}: HTTP 200`]: (res) => res.status === 200,
    [`${label}: JSON items`]: (res) => {
      if (label === 'product_detail') return res.status === 200;
      try {
        return Array.isArray(res.json().items);
      } catch (_) {
        return false;
      }
    },
  });
  return response;
}

// One iteration = one "browsing step" of four requests, mirroring a real buyer
// who lands on the catalog, pages once, and opens a product.
export default function () {
  const firstPage = record('global_first_page', getJson(`/api/products?limit=${PAGE_LIMIT}`, 'global_first_page'));

  let cursor = null;
  try {
    cursor = firstPage.json().nextCursor;
  } catch (_) {
    cursor = null;
  }
  if (cursor) {
    record('global_deep_page',
      getJson(`/api/products?limit=${PAGE_LIMIT}&cursor=${encodeURIComponent(cursor)}`, 'global_deep_page'));
  } else {
    CURSOR_MISSES.add(1);
  }

  record('price_filter', getJson(`/api/products?minPriceWon=1500&maxPriceWon=4000&limit=${PAGE_LIMIT}`,
    'price_filter'));

  record('needle_search', getJson(`/api/products?query=needle&limit=${PAGE_LIMIT}`, 'needle_search'));

  record('broad_search', getJson(`/api/products?query=broad&limit=${PAGE_LIMIT}`, 'broad_search'));

  let productId = null;
  try {
    productId = firstPage.json().items[0].product.productId;
  } catch (_) {
    productId = null;
  }
  if (productId) {
    record('product_detail', getJson(`/api/products/${productId}`, 'product_detail'));
  }
}

export function handleSummary(data) {
  const output = JSON.stringify(data, null, 2);
  const destinations = { stdout: `${output}\n` };
  if (SUMMARY_FILE) destinations[SUMMARY_FILE] = `${output}\n`;
  return destinations;
}
