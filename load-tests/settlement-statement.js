import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const SELLER_ID = __ENV.SELLER_ID || '';
const WINDOW = __ENV.WINDOW || '';
const SUMMARY_FILE = __ENV.SUMMARY_FILE || 'settlement-statement-summary.json';

const windows = {
  '10s': { from: '2026-01-01T00:00:01Z', to: '2026-01-01T00:00:11Z', count: 10, captured: 1000, reversal: 0, net: 1000, cursor: false },
  '100s': { from: '2026-01-01T00:00:01Z', to: '2026-01-01T00:01:41Z', count: 100, captured: 10000, reversal: 0, net: 10000, cursor: false },
  '1000s': { from: '2026-01-01T00:00:01Z', to: '2026-01-01T00:16:41Z', count: 100, captured: 100000, reversal: 0, net: 100000, cursor: true },
  full: { from: '2026-01-01T00:00:00Z', to: '2026-01-03T00:00:00Z', count: 100, captured: 100150, reversal: -50, net: 100100, cursor: true },
};

if (!BASE_URL) throw new Error('BASE_URL is required');
if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(SELLER_ID)) {
  throw new Error('SELLER_ID must be the seeded Seller UUID');
}
if (!Object.prototype.hasOwnProperty.call(windows, WINDOW)) throw new Error('WINDOW must be 10s, 100s, 1000s, or full');

const window = windows[WINDOW];
const latency = new Trend('settlement_statement_latency', true);
const itemCount = new Trend('settlement_statement_items');

export const options = {
  scenarios: {
    settlement_statement: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 20,
      maxDuration: '10m',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
};

export default function () {
  const url = `${BASE_URL}/api/sellers/${SELLER_ID}/settlement-statement`
    + `?from=${encodeURIComponent(window.from)}&to=${encodeURIComponent(window.to)}&limit=100`;
  const response = http.get(url, { tags: { window: WINDOW } });
  let body;
  try {
    body = response.json();
  } catch (_) {
    fail(`${WINDOW}: invalid JSON response`);
  }

  check(response, { [`${WINDOW}: HTTP 200`]: (result) => result.status === 200 })
    || fail(`${WINDOW}: HTTP ${response.status}`);
  const totals = body.totals || {};
  const hasCursor = typeof body.nextCursor === 'string' && body.nextCursor.length > 0;
  check(body, {
    [`${WINDOW}: Seller and item count`]: (result) => result.sellerId === SELLER_ID
      && Array.isArray(result.items) && result.items.length === window.count,
    [`${WINDOW}: exact totals`]: () => Number(totals.capturedAmountWon) === window.captured
      && Number(totals.refundReversalAmountWon) === window.reversal
      && Number(totals.netLedgerChangeWon) === window.net,
    [`${WINDOW}: cursor presence`]: () => hasCursor === window.cursor,
    [`${WINDOW}: stable row order`]: () => body.items.every((row, index) => row.journalId
      && (!index || body.items[index - 1].recordedAt < row.recordedAt
        || (body.items[index - 1].recordedAt === row.recordedAt
          && body.items[index - 1].journalId < row.journalId))),
  }) || fail(`${WINDOW}: response contract mismatch`);

  latency.add(response.timings.duration);
  itemCount.add(body.items.length);
  sleep(0.5);
}

export function handleSummary(data) {
  const latencyValues = data.metrics.settlement_statement_latency?.values || {};
  const itemValues = data.metrics.settlement_statement_items?.values || {};
  const summary = {
    experiment: 'EXP-020',
    window: WINDOW,
    requests: data.metrics.http_reqs?.values?.count || 0,
    itemsPerRequest: itemValues.med,
    latencyMs: {
      median: latencyValues.med,
      min: latencyValues.min,
      max: latencyValues.max,
      average: latencyValues.avg,
    },
    pacingSeconds: 0.5,
    vus: 1,
    note: '20 serial observations; descriptive only, no latency threshold or capacity claim.',
  };
  return { [SUMMARY_FILE]: JSON.stringify(summary, null, 2) };
}
