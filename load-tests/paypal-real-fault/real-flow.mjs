// 실제 ATLAS + 실제 PayPal Sandbox 결제 흐름에 Toxiproxy 장애를 주입하는 로컬 실험.
//
// 전제(start-app.sh 참고): 실험 전용 PostgreSQL 컨테이너, Mailpit, PayPal Toxiproxy,
// Toxiproxy 를 거쳐 실제 Sandbox 로 나가는 ATLAS 프로세스가 실행 중이어야 한다.
// - PayPal 호출은 stub 없이 실제 Sandbox 로 간다. 구매자 승인은 Sandbox 구매자 계정으로
//   headless Chromium 이 실제 PayPal 승인 화면에서 수행한다.
// - BOK ECOS 키가 없으므로 FX 스냅샷만 SYNTHETIC_FIXTURE 로 명시해 DB 에 넣는다.
// - 자격 증명·토큰·승인 URL·PayPal 주문 ID 는 출력하거나 결과 파일에 남기지 않는다.
// - 진실 원천 비교: PayPal 주문 상태는 Toxiproxy 를 거치지 않는 직접 연결로 조회한다.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const ROOT = new URL('../../', import.meta.url).pathname;
const BASE = process.env.BASE_URL || 'http://127.0.0.1:18980';
const MAIL = process.env.MAIL_API_URL || 'http://127.0.0.1:18025';
const TOXI = process.env.TOXI_API_URL || 'http://127.0.0.1:18475';
const PROXY = process.env.TOXI_PROXY_NAME || 'paypal-sandbox';
const PG_CONTAINER = process.env.PG_CONTAINER || 'atlas-real-pg';
const PRIVATE = process.env.PRIVATE_DIR || '/tmp/atlas-real';
const REPEAT = Number(process.env.REPEAT || 2);
const UNIT_PRICE_WON = 1_000; // 1,000원 ≒ 0.71 USD, Sandbox 소액
const SYNTHETIC_RATE = '1400.0000000000'; // 실측 환율 아님: SYNTHETIC_FIXTURE
const RECOVERY_WAIT_MS = Number(process.env.RECOVERY_WAIT_MS || 150_000);
const RUN = `real-${Date.now().toString(36)}`;
const log = (...xs) => console.error(new Date().toISOString().slice(11, 19), ...xs);

if (process.env.REAL_SANDBOX_OPT_IN !== 'YES') throw new Error('REAL_SANDBOX_OPT_IN=YES 가 필요하다');
for (const url of [BASE, MAIL, TOXI]) {
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(url)) throw new Error('loopback URL 만 허용한다');
}

const envLocal = Object.fromEntries(readFileSync(`${ROOT}.env.local`, 'utf8').split('\n')
  .filter((l) => /^[A-Z_]+=/.test(l)).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
for (const k of ['PAYPAL_CLIENT_ID', 'PAYPAL_CLIENT_SECRET', 'PAYPAL_SANDBOX_BUYER_EMAIL', 'PAYPAL_SANDBOX_BUYER_PASSWORD']) {
  if (!envLocal[k]) throw new Error(`.env.local 에 ${k} 가 없다`);
}
if (envLocal.PAYPAL_BASE_URL !== 'https://api-m.sandbox.paypal.com') throw new Error('Sandbox 전용');
const ACCOUNT_PASSWORD = readFileSync(`${PRIVATE}/acctpw`, 'utf8').trim();

const now = () => performance.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- 공통 HTTP ----------
async function api(method, path, { token, body, headers = {}, timeoutMs = 60_000 } = {}) {
  const start = now();
  try {
    const res = await fetch(`${BASE}${path}`, {
      method,
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(timeoutMs),
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* 본문 형식은 기록하지 않는다 */ }
    return { status: res.status, json, ms: now() - start };
  } catch (error) {
    return { status: 0, json: null, ms: now() - start, error: error.name };
  }
}
function expect(res, status, op) {
  if (res.status !== status) throw new Error(`${op}: HTTP ${res.status} ${res.json?.code ?? ''}`);
  return res.json;
}

// ---------- Toxiproxy ----------
async function toxi(method, path, body) {
  const res = await fetch(`${TOXI}${path}`, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  if (!res.ok && !(method === 'DELETE' && res.status === 404)) throw new Error(`toxiproxy ${method} ${path}: ${res.status}`);
}
async function clearToxics() {
  const res = await fetch(`${TOXI}/proxies/${PROXY}/toxics`);
  for (const t of await res.json()) await toxi('DELETE', `/proxies/${PROXY}/toxics/${t.name}`);
}
async function applyToxic(toxic) {
  await clearToxics();
  if (toxic) await toxi('POST', `/proxies/${PROXY}/toxics`, { name: 'experiment', stream: 'downstream', toxicity: 1, ...toxic });
}
async function verifyProxyTopology() {
  const p = await (await fetch(`${TOXI}/proxies/${PROXY}`)).json();
  if (!p.enabled || p.upstream !== 'api-m.sandbox.paypal.com:443') throw new Error('PayPal 프록시가 Sandbox upstream 이 아니다');
}

// ---------- PostgreSQL(실험 전용 컨테이너) ----------
function sql(query) {
  return execFileSync('docker', ['exec', '-i', PG_CONTAINER, 'psql', '-q', '-U', 'atlas', '-d', 'atlas', '-v', 'ON_ERROR_STOP=1', '-At', '-F', '\t'],
    { input: query, encoding: 'utf8' }).trim();
}
const uuidOnly = (v) => { if (!/^[0-9a-f-]{36}$/i.test(v)) throw new Error('UUID 아님'); return v; };

// ---------- PayPal 진실 원천(직접 연결, Toxiproxy 미경유) ----------
let paypalToken = null;
async function paypalDirect(path) {
  if (!paypalToken) {
    const basic = Buffer.from(`${envLocal.PAYPAL_CLIENT_ID}:${envLocal.PAYPAL_CLIENT_SECRET}`).toString('base64');
    const r = await fetch('https://api-m.sandbox.paypal.com/v1/oauth2/token', {
      method: 'POST', headers: { Authorization: `Basic ${basic}`, 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'grant_type=client_credentials',
    });
    if (!r.ok) throw new Error(`PayPal 직접 OAuth 실패: ${r.status}`);
    paypalToken = (await r.json()).access_token;
  }
  const r = await fetch(`https://api-m.sandbox.paypal.com${path}`, { headers: { Authorization: `Bearer ${paypalToken}` } });
  if (!r.ok) throw new Error(`PayPal 직접 조회 실패: ${r.status}`);
  return r.json();
}
async function paypalTruth(providerOrderId) {
  if (!providerOrderId) return { orderStatus: null, completedCaptures: 0, authorizations: [] };
  const order = await paypalDirect(`/v2/checkout/orders/${encodeURIComponent(providerOrderId)}`);
  const payments = order.purchase_units?.[0]?.payments ?? {};
  const captures = (payments.captures ?? []).filter((c) => c.status === 'COMPLETED');
  return {
    orderStatus: order.status,
    completedCaptures: captures.length,
    capturedMinor: captures.reduce((s, c) => s + Math.round(Number(c.amount.value) * 100), 0),
    authorizations: (payments.authorizations ?? []).map((a) => a.status),
  };
}

// ---------- 계정·카탈로그 fixture (실제 앱 API) ----------
async function verificationToken(email) {
  for (let i = 0; i < 30; i += 1) {
    const s = await (await fetch(`${MAIL}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}&limit=5`)).json();
    const id = s.messages?.[0]?.ID;
    if (id) {
      const m = await (await fetch(`${MAIL}/api/v1/message/${id}`)).json();
      const match = (m.Text || '').match(/one-time token:\s*(\S+)/i);
      if (match) return match[1];
    }
    await sleep(500);
  }
  throw new Error('검증 메일 없음');
}
async function account(role, index) {
  const email = `${RUN}-${role}-${index}@example.test`;
  expect(await api('POST', '/api/auth/signup', { body: { email, password: ACCOUNT_PASSWORD } }), 202, 'signup');
  expect(await api('POST', '/api/auth/verify-email', { body: { token: await verificationToken(email) } }), 204, 'verify');
  const login = async () => {
    const body = expect(await api('POST', '/api/auth/login', { body: { email, password: ACCOUNT_PASSWORD } }), 200, 'login');
    return body.tokens ?? body;
  };
  return { ...(await login()), relogin: login };
}
async function setupCatalog() {
  const seller = await account('seller', 0);
  const sellerId = expect(await api('POST', '/api/sellers', { token: seller.accessToken }), 201, 'seller').sellerId;
  const sellerToken = (await seller.relogin()).accessToken;
  const product = expect(await api('POST', `/api/sellers/${sellerId}/products`,
    { token: sellerToken, body: { name: `실제 Sandbox 장애 실험 ${RUN}`, description: 'EXP-050 실험 상품' } }), 201, 'product');
  const skuId = `${RUN}-sku`;
  expect(await api('POST', `/api/sellers/${sellerId}/products/${product.productId}/skus`,
    { token: sellerToken, body: { skuId, priceWon: UNIT_PRICE_WON, initialStock: 500 } }), 201, 'sku');
  return { sellerId, skuId };
}
async function createCheckout(catalog, buyer, n) {
  const ref = encodeURIComponent(buyer.buyerRef);
  expect(await api('PUT', `/api/buyers/${ref}/cart/items/${catalog.sellerId}/${encodeURIComponent(catalog.skuId)}`,
    { token: buyer.accessToken, body: { quantity: 1 } }), 200, 'cart');
  const checkout = expect(await api('POST', `/api/buyers/${ref}/cart/checkout`,
    { token: buyer.accessToken, headers: { 'Idempotency-Key': `${RUN}-checkout-${n}` } }), 200, 'checkout');
  const checkoutId = uuidOnly(checkout.checkoutId);
  // FX 만 합성: ATLAS 는 결제 제출 시 attempt 행을 INSERT ... ON CONFLICT DO NOTHING 으로
  // 만든다. 같은 checkout 의 NOT_STARTED 행을 FX 스냅샷과 함께 먼저 넣어 BOK ECOS 조회를
  // 건너뛴다. 실제 PayPal 금액은 이 스냅샷으로 결정되고 ADR-0030 불변식은 그대로 검증된다.
  const totalWon = Number(checkout.totalWon);
  if (!Number.isInteger(totalWon) || totalWon <= 0 || totalWon > 10_000) throw new Error('소액 checkout 만 허용한다');
  sql(`INSERT INTO payment_attempt (attempt_id, checkout_id, state, payment_phase, authorization_active, total_won,
      provider_currency, provider_amount_minor, fx_rate_won_per_usd, fx_rate_source, fx_rate_as_of)
    VALUES (uuidv7(), '${checkoutId}', 'NOT_STARTED', 'AUTHORIZATION', false, ${totalWon},
      'USD', round(${totalWon}::numeric / ${SYNTHETIC_RATE} * 100), ${SYNTHETIC_RATE}, 'SYNTHETIC_FIXTURE', current_date);`);
  const inserted = sql(`SELECT count(*) FROM payment_attempt WHERE checkout_id = '${checkoutId}' AND fx_rate_source = 'SYNTHETIC_FIXTURE' AND state = 'NOT_STARTED';`);
  if (inserted !== '1') throw new Error('FX fixture attempt 생성 실패');
  return { checkoutId, buyer };
}

// ---------- 실제 PayPal 승인(headless) ----------
let browser;
async function approve(approvalUrl) {
  if (!browser) browser = await chromium.launch({ headless: true });
  // 승인마다 새 컨텍스트: 이전 승인의 PayPal 로그인 세션이 화면 순서를 바꾸지 않게 한다.
  const context = await browser.newContext({ locale: 'en-US' });
  const page = await context.newPage();
  let returnUrl = null;
  // 브라우저가 ATLAS return URL 로 돌아오는 순간을 가로채서, capture 호출은 실험 코드가
  // 장애 조건을 건 뒤 직접 수행한다.
  await page.route(`${BASE}/**`, async (route) => {
    returnUrl = route.request().url();
    await route.fulfill({ status: 204, body: '' });
  });
  page.setDefaultTimeout(15_000);
  try {
    // 실제 Sandbox 화면 순서(2026-11 확인): 이메일 → 비밀번호 → 결제 검토 → 승인 버튼 → return_url.
    await page.goto(approvalUrl, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await page.locator('#email').fill(envLocal.PAYPAL_SANDBOX_BUYER_EMAIL);
    await page.locator('#btnNext').click();
    await page.locator('#password').fill(envLocal.PAYPAL_SANDBOX_BUYER_PASSWORD);
    await page.locator('#btnLogin').click();
    await page.locator('[data-testid="submit-button-initial"]').click({ timeout: 45_000 });
    const deadline = Date.now() + 45_000;
    while (!returnUrl && Date.now() < deadline) await sleep(250);
    if (!returnUrl) throw new Error('PayPal 승인 후 return URL 로 돌아오지 않았다');
    const u = new URL(returnUrl);
    if (u.origin !== BASE || !/^\/api\/checkouts\/[0-9a-f-]{36}\/payment\/return$/.test(u.pathname)) throw new Error('예상 밖 return URL');
    return `${u.pathname}${u.search}`;
  } catch (error) {
    mkdirSync(`${PRIVATE}/shots`, { recursive: true });
    await page.screenshot({ path: `${PRIVATE}/shots/approve-${Date.now()}.png` }).catch(() => {});
    throw error;
  } finally {
    await context.close();
  }
}

// ---------- 상태 수집 ----------
function dbState(checkoutId) {
  const row = sql(`SELECT c.state, a.state, a.provider_order_id,
      (SELECT count(*) FROM settlement_journal j WHERE j.checkout_id = c.checkout_id),
      (SELECT string_agg(o.state, ',' ORDER BY o.order_id) FROM purchase_order o WHERE o.checkout_id = c.checkout_id),
      a.provider_amount_minor
    FROM buyer_checkout c JOIN payment_attempt a ON a.checkout_id = c.checkout_id WHERE c.checkout_id = '${uuidOnly(checkoutId)}';`);
  const [checkout, payment, providerOrderId, journals, orders, amountMinor] = row.split('\t');
  return { checkout, payment, providerOrderId: providerOrderId || null, journals: Number(journals), orders, amountMinor: Number(amountMinor) };
}
const TERMINAL = new Set(['PAID', 'CANCELLED', 'EXPIRED', 'FAILED']);
async function waitForConvergence(checkoutId) {
  const start = now();
  let state = dbState(checkoutId);
  while (!TERMINAL.has(state.checkout) && now() - start < RECOVERY_WAIT_MS) {
    await sleep(1000);
    state = dbState(checkoutId);
  }
  return { state, ms: Math.round(now() - start), converged: TERMINAL.has(state.checkout) };
}

// ---------- 카탈로그 동시 부하(격리 관찰) ----------
function catalogLoad(rps) {
  const samples = []; let stop = false;
  const loop = (async () => {
    while (!stop) {
      api('GET', '/api/products?limit=20', { timeoutMs: 10_000 }).then((r) => samples.push(r));
      await sleep(1000 / rps);
    }
  })();
  return async () => {
    stop = true; await loop; await sleep(1000);
    const ms = samples.filter((s) => s.status === 200).map((s) => s.ms).sort((a, b) => a - b);
    const q = (p) => (ms.length ? Math.round(ms[Math.min(ms.length - 1, Math.floor(p * ms.length))]) : null);
    return { requests: samples.length, failures: samples.filter((s) => s.status !== 200).length, p50: q(0.5), p95: q(0.95), max: ms.at(-1) ? Math.round(ms.at(-1)) : null };
  };
}

// ---------- 시나리오 ----------
// phase=submit: 주문 생성(POST /payment) 중 장애. phase=capture: 승인 후 return(승인·capture) 중 장애.
const CONDITIONS = [
  { name: 'baseline', phase: null, toxic: null },
  { name: 'submit-latency-3s', phase: 'submit', toxic: { type: 'latency', attributes: { latency: 3000, jitter: 0 } } },
  { name: 'submit-response-lost', phase: 'submit', toxic: { type: 'timeout', attributes: { timeout: 15000 } } },
  { name: 'capture-latency-3s', phase: 'capture', toxic: { type: 'latency', attributes: { latency: 3000, jitter: 0 } } },
  { name: 'capture-response-lost', phase: 'capture', toxic: { type: 'timeout', attributes: { timeout: 15000 } } },
  { name: 'capture-reset', phase: 'capture', toxic: { type: 'reset_peer', attributes: { timeout: 0 } } },
];
const only = process.env.CONDITIONS ? new Set(process.env.CONDITIONS.split(',')) : null;

async function runCondition(cond, catalog, buyers, counter) {
  // access token 수명(약 15분)보다 전체 실험이 길다. 조건마다 다시 로그인한다.
  for (const [i, buyer] of buyers.entries()) buyers[i] = { ...buyer, ...(await buyer.relogin()) };
  const items = [];
  for (let i = 0; i < REPEAT; i += 1) items.push(await createCheckout(catalog, buyers[i % buyers.length], counter.n++));
  const stopCatalog = catalogLoad(10);

  log(cond.name, 'checkouts ready', items.length);
  // 1) 주문 생성
  if (cond.phase === 'submit') await applyToxic(cond.toxic);
  const submits = await Promise.all(items.map((it) => api('POST', `/api/checkouts/${it.checkoutId}/payment`, { token: it.buyer.accessToken })));
  if (cond.phase === 'submit') await applyToxic(null);

  // 2) 실제 승인: 주문 생성 응답이 없었다면 복구 경로가 승인 URL 을 되살리는지 확인
  const results = [];
  for (const [i, it] of items.entries()) {
    let approvalUrl = submits[i].json?.paymentApprovalUrl;
    const submitRecoveryStart = now();
    for (let t = 0; !approvalUrl && t < 60; t += 1) {
      await sleep(2000);
      const r = await api('POST', `/api/checkouts/${it.checkoutId}/reconcile`, { token: it.buyer.accessToken, headers: { 'Idempotency-Key': `${RUN}-rec-${it.checkoutId}-${t}` } });
      approvalUrl = r.json?.paymentApprovalUrl;
    }
    results.push({
      submit: { status: submits[i].status, ms: Math.round(submits[i].ms), state: submits[i].json?.paymentState ?? submits[i].json?.code ?? submits[i].error },
      approvalRecoveredMs: submits[i].json?.paymentApprovalUrl ? 0 : Math.round(now() - submitRecoveryStart),
      approvalUrl,
    });
  }
  log(cond.name, 'submitted', results.map((r) => r.submit.status).join(','));
  const returnPaths = [];
  for (const r of results) returnPaths.push(r.approvalUrl ? await approve(r.approvalUrl) : null);

  log(cond.name, 'approved', returnPaths.filter(Boolean).length);
  // 3) 승인 반영·capture
  if (cond.phase === 'capture') await applyToxic(cond.toxic);
  const returns = await Promise.all(returnPaths.map((p) => (p ? api('GET', p, { timeoutMs: 60_000 }) : null)));
  if (cond.phase === 'capture') await applyToxic(null);
  const catalogStats = await stopCatalog();

  log(cond.name, 'returned', returns.map((r) => r?.status).join(','));
  // 4) 복구 수렴(복구 worker 만 사용, 수동 reconcile 없음)과 PayPal 진실 비교
  for (const [i, it] of items.entries()) {
    const ret = returns[i];
    const conv = await waitForConvergence(it.checkoutId);
    const truth = await paypalTruth(conv.state.providerOrderId);
    const r = results[i];
    delete r.approvalUrl;
    Object.assign(r, {
      return: ret ? { status: ret.status, ms: Math.round(ret.ms), state: ret.json?.paymentState ?? ret.json?.code ?? ret.error } : null,
      converged: conv.converged, convergeMs: conv.ms,
      atlas: { checkout: conv.state.checkout, payment: conv.state.payment, orders: conv.state.orders, journals: conv.state.journals },
      paypal: { orderStatus: truth.orderStatus, completedCaptures: truth.completedCaptures, authorizations: truth.authorizations },
      invariants: {
        atMostOneCapture: truth.completedCaptures <= 1,
        paidIffCaptured: (conv.state.checkout === 'PAID') === (truth.completedCaptures === 1),
        capturedAmountMatchesSnapshot: truth.completedCaptures === 0 || truth.capturedMinor === conv.state.amountMinor,
        ledgerMatches: (conv.state.checkout === 'PAID') === (conv.state.journals === 1),
      },
    });
  }
  return { condition: cond.name, catalog: catalogStats, checkouts: results };
}

async function main() {
  await verifyProxyTopology();
  await clearToxics();
  const catalog = await setupCatalog();
  const buyers = [];
  for (let i = 0; i < REPEAT; i += 1) buyers.push(await account('buyer', i));
  const counter = { n: 0 };
  const report = { run: RUN, startedAt: new Date().toISOString(), repeat: REPEAT, unitPriceWon: UNIT_PRICE_WON, fx: 'SYNTHETIC_FIXTURE 1400 KRW/USD', conditions: [] };
  try {
    for (const cond of CONDITIONS) {
      if (only && !only.has(cond.name)) continue;
      const result = await runCondition(cond, catalog, buyers, counter);
      report.conditions.push(result);
      console.log(JSON.stringify({ condition: result.condition, catalog: result.catalog, checkouts: result.checkouts.map((c) => ({ submit: c.submit, return: c.return, converged: c.converged, convergeMs: c.convergeMs, atlas: c.atlas.checkout, paypalCaptures: c.paypal.completedCaptures, invariants: Object.values(c.invariants).every(Boolean) })) }));
    }
  } finally {
    await clearToxics().catch(() => {});
    await browser?.close();
    mkdirSync(`${PRIVATE}/results`, { recursive: true });
    writeFileSync(`${PRIVATE}/results/${RUN}.json`, `${JSON.stringify(report, null, 2)}\n`);
    console.log(`결과: ${PRIVATE}/results/${RUN}.json`);
  }
  const violations = report.conditions.flatMap((c) => c.checkouts).filter((c) => !Object.values(c.invariants).every(Boolean));
  if (violations.length) { console.error(`불변식 위반 ${violations.length}건`); process.exitCode = 1; }
}

main().catch((error) => { console.error(error.message); process.exitCode = 1; });
