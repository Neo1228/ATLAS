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
import { createHmac } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { chromium } from 'playwright';

const ROOT = new URL('../../', import.meta.url).pathname;
const BASE = process.env.BASE_URL || 'http://127.0.0.1:18980';
const MAIL = process.env.MAIL_API_URL || 'http://127.0.0.1:18025';
const TOXI = process.env.TOXI_API_URL || 'http://127.0.0.1:18475';
const PROXY = process.env.TOXI_PROXY_NAME || 'paypal-sandbox';
const CAPTURE_PROXY = process.env.TOXI_CAPTURE_PROXY_NAME || 'paypal-capture';
const PG_CONTAINER = process.env.PG_CONTAINER || 'atlas-real-pg';
const PRIVATE = process.env.PRIVATE_DIR || '/tmp/atlas-real';
const RESULTS_DIR = process.env.RESULTS_DIR || `${PRIVATE}/results`;
const REPEAT = Number(process.env.REPEAT || 2);
const UNIT_PRICE_WON = Number(process.env.UNIT_PRICE_WON || 1_000); // payout flow는 환율 반올림 상한을 넘도록 10,000원
const SYNTHETIC_RATE = '1400.0000000000'; // 실측 환율 아님: SYNTHETIC_FIXTURE
const RECOVERY_WAIT_MS = Number(process.env.RECOVERY_WAIT_MS || 150_000);
const RECOVERY_WORKER_ENABLED = process.env.ATLAS_PAYMENT_RECOVERY_WORKER_ENABLED !== 'false';
const PAYOUT_E2E = process.env.PAYOUT_E2E === 'YES';
if (!Number.isInteger(REPEAT) || REPEAT < 1 || REPEAT > 5) throw new Error('REPEAT 는 1-5 사이여야 한다');
if (!Number.isInteger(RECOVERY_WAIT_MS) || RECOVERY_WAIT_MS < 1000 || RECOVERY_WAIT_MS > 300_000) {
  throw new Error('RECOVERY_WAIT_MS 는 1000-300000 ms 사이여야 한다');
}
const RUN = `real-${Date.now().toString(36)}`;
const activeCatalogStops = new Set();
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
async function clearToxics(proxyName = PROXY) {
  const res = await fetch(`${TOXI}/proxies/${proxyName}/toxics`);
  for (const toxic of await res.json()) {
    await toxi('DELETE', `/proxies/${proxyName}/toxics/${toxic.name}`);
  }
}
async function applyToxic(toxic, proxyName = PROXY) {
  await clearToxics(proxyName);
  if (toxic) await toxi('POST', `/proxies/${proxyName}/toxics`, {
    name: 'experiment', stream: 'downstream', toxicity: 1, ...toxic,
  });
}
async function verifyProxyTopology() {
  for (const name of [PROXY, CAPTURE_PROXY]) {
    const proxy = await (await fetch(`${TOXI}/proxies/${name}`)).json();
    if (!proxy.enabled || proxy.upstream !== 'api-m.sandbox.paypal.com:443') {
      throw new Error(`PayPal proxy topology is invalid: ${name}`);
    }
  }
}

// ---------- PostgreSQL(실험 전용 컨테이너) ----------
function sql(query) {
  return execFileSync('docker', ['exec', '-i', PG_CONTAINER, 'psql', '-q', '-U', 'atlas', '-d', 'atlas', '-v', 'ON_ERROR_STOP=1', '-At', '-F', '\t'],
    { input: query, encoding: 'utf8' }).replace(/\r?\n$/, ''); // 앞쪽 빈 열(탭)을 보존한다
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
async function account(role, index, emailOverride = null) {
  const email = emailOverride ?? `${RUN}-${role}-${index}@example.test`;
  expect(await api('POST', '/api/auth/signup', { body: { email, password: ACCOUNT_PASSWORD } }), 202, 'signup');
  expect(await api('POST', '/api/auth/verify-email', { body: { token: await verificationToken(email) } }), 204, 'verify');
  const login = async () => {
    const body = expect(await api('POST', '/api/auth/login', { body: { email, password: ACCOUNT_PASSWORD } }), 200, 'login');
    return body.tokens ?? body;
  };
  return { ...(await login()), email, relogin: login };
}
async function setupCatalog(emailOverride = null, skuOverride = null) {
  const seller = await account('seller', 0, emailOverride);
  const sellerId = expect(await api('POST', '/api/sellers', { token: seller.accessToken }), 201, 'seller').sellerId;
  const sellerToken = (await seller.relogin()).accessToken;
  const product = expect(await api('POST', `/api/sellers/${sellerId}/products`,
    { token: sellerToken, body: { name: `실제 Sandbox 장애 실험 ${RUN}`, description: 'EXP-050 실험 상품' } }), 201, 'product');
  const skuId = skuOverride ?? `${RUN}-sku`;
  expect(await api('POST', `/api/sellers/${sellerId}/products/${product.productId}/skus`,
    { token: sellerToken, body: { skuId, priceWon: UNIT_PRICE_WON, initialStock: 500 } }), 201, 'sku');
  return { sellerId, skuId, seller };
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

async function prepareCaptureWarmup(catalog, counter) {
  const buyer = await account('capture-warmup', counter.n++);
  const item = await createCheckout(catalog, buyer, counter.n++);
  const submit = await api('POST', `/api/checkouts/${item.checkoutId}/payment`, { token: buyer.accessToken });
  const approvalUrl = submit.json?.paymentApprovalUrl;
  if (submit.status !== 200 || !approvalUrl) throw new Error('capture connection warmup could not create approval');
  return { item, returnPath: await approve(approvalUrl) };
}

async function completeCaptureWarmup(warmup) {
  const response = await api('GET', warmup.returnPath, { timeoutMs: 60_000 });
  const state = dbState(warmup.item.checkoutId);
  if (response.status !== 200 || response.json?.state !== 'PAID'
      || response.json?.paymentState !== 'CAPTURED' || state.checkout !== 'PAID'
      || state.payment !== 'CAPTURED' || state.journals !== 1) {
    throw new Error(`capture warmup not applied: http=${response.status} checkout=${response.json?.state ?? 'none'} payment=${response.json?.paymentState ?? 'none'} db=${state.checkout}/${state.payment} journal=${state.journals}`);
  }
  return {
    returnStatus: response.status, checkoutState: response.json.state,
    paymentState: response.json.paymentState, journals: state.journals,
  };
}

// ---------- 상태 수집 ----------
function dbState(checkoutId) {
  const row = sql(`SELECT c.state, a.state, a.provider_order_id,
      (SELECT count(*) FROM settlement_journal j WHERE j.checkout_id = c.checkout_id),
      (SELECT string_agg(o.state, ',' ORDER BY o.order_id) FROM purchase_order o WHERE o.checkout_id = c.checkout_id),
      a.provider_amount_minor, COALESCE(r.status, 'NONE'), COALESCE(r.attempt_count, -1),
      COALESCE(r.available_at <= CURRENT_TIMESTAMP, false),
      COALESCE(r.lease_until > CURRENT_TIMESTAMP, false)
    FROM buyer_checkout c
    JOIN payment_attempt a ON a.checkout_id = c.checkout_id
    LEFT JOIN payment_recovery_job r ON r.payment_attempt_id = a.attempt_id
    WHERE c.checkout_id = '${uuidOnly(checkoutId)}';`);
  const [checkout, payment, providerOrderId, journals, orders, amountMinor,
    recoveryStatus, recoveryAttemptCount, recoveryDue, recoveryLeased] = row.split('\t');
  return {
    checkout, payment, providerOrderId: providerOrderId || null, journals: Number(journals), orders,
    amountMinor: Number(amountMinor),
    recovery: {
      status: recoveryStatus, attemptCount: Number(recoveryAttemptCount),
      due: recoveryDue === 't', leased: recoveryLeased === 't',
    },
  };
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
  const samples = [];
  const inFlight = new Set();
  let stopping = false;
  let stopPromise = null;
  const loop = (async () => {
    while (!stopping) {
      let request;
      request = api('GET', '/api/products?limit=20', { timeoutMs: 10_000 })
        .then((response) => samples.push(response))
        .finally(() => inFlight.delete(request));
      inFlight.add(request);
      await sleep(1000 / rps);
    }
  })();
  const stop = () => {
    if (!stopPromise) {
      stopping = true;
      stopPromise = (async () => {
        await loop;
        await Promise.allSettled([...inFlight]);
        const ms = samples.filter((sample) => sample.status === 200)
          .map((sample) => sample.ms).sort((a, b) => a - b);
        const q = (p) => (ms.length ? Math.round(ms[Math.min(ms.length - 1, Math.floor(p * ms.length))]) : null);
        const stats = {
          requests: samples.length, failures: samples.filter((sample) => sample.status !== 200).length,
          p50: q(0.5), p95: q(0.95), max: ms.at(-1) ? Math.round(ms.at(-1)) : null,
        };
        activeCatalogStops.delete(stop);
        return stats;
      })();
    }
    return stopPromise;
  };
  activeCatalogStops.add(stop);
  return stop;
}

// ---------- 시나리오 ----------
// phase=submit: 주문 생성(POST /payment) 중 장애. phase=approval-return: 승인 후 첫 provider 요청(GET order) 중 장애.
// capture-response-lost exceeds the fixed 10s client read timeout; paired warmups keep TLS handshake out of the fault window.
const CONDITIONS = [
  { name: 'baseline', phase: null, toxic: null },
  { name: 'submit-latency-3s', phase: 'submit', toxic: { type: 'latency', attributes: { latency: 3000, jitter: 0 } } },
  { name: 'submit-response-lost', phase: 'submit', toxic: { type: 'timeout', attributes: { timeout: 15000 } } },
  { name: 'approval-order-lookup-latency-3s', phase: 'approval-return', toxic: { type: 'latency', attributes: { latency: 3000, jitter: 0 } } },
  { name: 'approval-order-lookup-response-lost', phase: 'approval-return', toxic: { type: 'timeout', attributes: { timeout: 15000 } } },
  { name: 'approval-order-lookup-reset', phase: 'approval-return', toxic: { type: 'reset_peer', attributes: { timeout: 0 } } },
  { name: 'capture-latency-3s', phase: 'capture', toxic: { type: 'latency', attributes: { latency: 3000, jitter: 0 } } },
  { name: 'capture-response-lost', phase: 'capture', toxic: { type: 'latency', attributes: { latency: 12_000, jitter: 0 } } },
  { name: 'capture-response-reset', phase: 'capture', toxic: { type: 'reset_peer', attributes: { timeout: 0 } } },
  { name: 'capture-response-timeout', phase: 'capture', toxic: { type: 'timeout', attributes: { timeout: 15000 } } },
];
const only = process.env.CONDITIONS ? new Set(process.env.CONDITIONS.split(',')) : null;
if (only) {
  const unknown = [...only].filter((name) => !CONDITIONS.some((condition) => condition.name === name));
  if (unknown.length) throw new Error(`Unknown CONDITIONS: ${unknown.join(',')}`);
}

async function runCondition(cond, catalog, buyers, counter) {
  // access token 수명(약 15분)보다 전체 실험이 길다. 조건마다 다시 로그인한다.
  for (const [i, buyer] of buyers.entries()) buyers[i] = { ...buyer, ...(await buyer.relogin()) };
  const warmups = [];
  const warmupObservations = [];
  if (cond.phase === 'capture') {
    // Capture 전용 fault는 같은 공유 TLS pool을 fault-free capture로 데운 뒤 적용한다.
    for (let i = 0; i < REPEAT; i += 1) {
      warmups.push(await prepareCaptureWarmup(catalog, counter));
    }
  }
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
  const returns = [];
  const returnSnapshots = [];
  if (cond.phase === 'capture') {
    for (const [i, warmup] of warmups.entries()) {
      warmupObservations.push(await completeCaptureWarmup(warmup));
      await applyToxic(cond.toxic, CAPTURE_PROXY);
      const ret = returnPaths[i]
        ? await api('GET', returnPaths[i], { timeoutMs: 60_000 })
        : null;
      await applyToxic(null, CAPTURE_PROXY);
      returns.push(ret);
      const stateAtReturn = dbState(items[i].checkoutId);
      const paypalAtReturn = await paypalTruth(stateAtReturn.providerOrderId);
      returnSnapshots.push({ stateAtReturn, paypalAtReturn });
    }
  } else {
    if (cond.phase === 'approval-return') await applyToxic(cond.toxic);
    returns.push(...await Promise.all(returnPaths.map((path) => (path ? api('GET', path, { timeoutMs: 60_000 }) : null))));
    if (cond.phase === 'approval-return') await applyToxic(null);
  }
  const catalogStats = await stopCatalog();

  log(cond.name, 'returned', returns.map((r) => r?.status).join(','));
  // Worker는 기본 활성화한다. disabled 관측 run에서는 API·DB·복구 job·PayPal 진실을 먼저 기록한 뒤에만 buyer reconcile을 호출한다.
  for (const [i, it] of items.entries()) {
    const ret = returns[i];
    const snapshot = returnSnapshots[i];
    const stateAtReturn = snapshot ? snapshot.stateAtReturn : dbState(it.checkoutId);
    const paypalAtReturn = snapshot
      ? snapshot.paypalAtReturn : await paypalTruth(stateAtReturn.providerOrderId);
    let manualReconcile = null;
    const atReturnUnknown = ret?.json?.state === 'PAYMENT_UNKNOWN'
      && ret.json.paymentState === 'UNKNOWN'
      && stateAtReturn.checkout === 'PAYMENT_UNKNOWN'
      && stateAtReturn.payment === 'UNKNOWN'
      && stateAtReturn.journals === 0;
    const captureFaultObserved = cond.phase !== 'capture' ? null
      : cond.name === 'capture-latency-3s'
        ? ret?.json?.state === 'PAID' && ret.json.paymentState === 'CAPTURED'
          && stateAtReturn.checkout === 'PAID' && stateAtReturn.payment === 'CAPTURED'
          && stateAtReturn.journals === 1 && paypalAtReturn.completedCaptures === 1
        : cond.name === 'capture-response-lost'
          ? atReturnUnknown && paypalAtReturn.completedCaptures === 1
            && stateAtReturn.recovery.status === 'PENDING'
            && stateAtReturn.recovery.attemptCount === 0
            && !stateAtReturn.recovery.due && !stateAtReturn.recovery.leased
          : atReturnUnknown;
    if (!RECOVERY_WORKER_ENABLED && ret) {
      const response = await api('POST', `/api/checkouts/${it.checkoutId}/reconcile`, {
        token: it.buyer.accessToken,
        headers: { 'Idempotency-Key': `${RUN}-capture-reconcile-${counter.n++}` },
      });
      manualReconcile = {
        status: response.status, checkoutState: response.json?.state ?? null,
        paymentState: response.json?.paymentState ?? null,
      };
    }
    const conv = await waitForConvergence(it.checkoutId);
    const truth = await paypalTruth(conv.state.providerOrderId);
    const orderStates = conv.state.orders ? conv.state.orders.split(',') : [];
    const r = results[i];
    delete r.approvalUrl;
    Object.assign(r, {
      return: ret ? {
        status: ret.status, ms: Math.round(ret.ms), checkoutState: ret.json?.state ?? null,
        paymentState: ret.json?.paymentState ?? null,
        state: ret.json?.paymentState ?? ret.json?.code ?? ret.error,
      } : null,
      manualReconcile,
      paypalAtReturn: { orderStatus: paypalAtReturn.orderStatus, completedCaptures: paypalAtReturn.completedCaptures },
      atlasAtReturn: {
        checkout: stateAtReturn.checkout, payment: stateAtReturn.payment,
        orders: stateAtReturn.orders, journals: stateAtReturn.journals,
        recovery: stateAtReturn.recovery,
      },
      converged: conv.converged, convergeMs: conv.ms,
      atlas: { checkout: conv.state.checkout, payment: conv.state.payment, orders: conv.state.orders, journals: conv.state.journals },
      paypal: { orderStatus: truth.orderStatus, completedCaptures: truth.completedCaptures, authorizations: truth.authorizations },
      captureFaultObserved,
      invariants: {
        atMostOneCapture: truth.completedCaptures <= 1,
        paidIffCaptured: (conv.state.checkout === 'PAID') === (truth.completedCaptures === 1),
        capturedAmountMatchesSnapshot: truth.completedCaptures === 0 || truth.capturedMinor === conv.state.amountMinor,
        paymentMatchesPayPalCapture: (conv.state.payment === 'CAPTURED') === (truth.completedCaptures === 1),
        ordersMatchCheckout: conv.state.checkout === 'PAID'
          ? orderStates.length > 0 && orderStates.every((state) => state === 'PAID')
          : !orderStates.includes('PAID'),
        ledgerMatches: (conv.state.checkout === 'PAID') === (conv.state.journals === 1),
      },
    });
  }
  return { condition: cond.name, catalog: catalogStats, warmups: warmupObservations, checkouts: results };
}

function totpCode(secret, at = Date.now()) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0;
  let value = 0;
  const bytes = [];
  for (const char of secret.replace(/=+$/, '').toUpperCase()) {
    value = (value << 5) | alphabet.indexOf(char);
    bits += 5;
    if (bits >= 8) { bits -= 8; bytes.push((value >> bits) & 255); }
  }
  const counter = BigInt(Math.floor(at / 30_000));
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(counter);
  const digest = createHmac('sha1', Buffer.from(bytes)).update(msg).digest();
  const offset = digest[digest.length - 1] & 15;
  const number = (digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
  return String(number).padStart(6, '0');
}

async function operator(label) {
  const user = await account(`payout-operator-${label}`, 0);
  const enrollment = expect(await api('POST', '/api/auth/mfa/enroll', { token: user.accessToken }), 200, 'operator MFA enrollment');
  const enabled = expect(await api('POST', '/api/auth/mfa/enable', {
    token: user.accessToken, body: { code: totpCode(enrollment.secret) },
  }), 200, 'operator MFA enable');
  const granted = sql(`INSERT INTO payout_operator_account(account_id, granted_by) VALUES ('${uuidOnly(user.accountId)}', 'EXP-102') ON CONFLICT (account_id) DO UPDATE SET revoked_at = NULL`);
  if (granted !== '') throw new Error('operator grant SQL returned unexpected output');
  const login = expect(await api('POST', '/api/auth/login', {
    body: { email: user.email, password: ACCOUNT_PASSWORD },
  }), 200, 'operator login');
  if (!login.mfaRequired || login.tokens) throw new Error('operator login did not require second factor');
  const tokens = expect(await api('POST', '/api/auth/mfa/challenge', {
    body: { challengeToken: login.challengeToken, recoveryCode: enabled.recoveryCodes[0] },
  }), 200, 'operator MFA challenge');
  if (!tokens.accessToken) throw new Error('operator second-factor challenge returned no access token');
  return { ...tokens, email: user.email };
}

async function createPayoutCheckout(catalog, buyer, n) {
  const item = await createCheckout(catalog, buyer, n);
  const submit = await api('POST', `/api/checkouts/${item.checkoutId}/payment`, { token: buyer.accessToken });
  const approvalUrl = submit.json?.paymentApprovalUrl;
  if (submit.status !== 200 || !approvalUrl) throw new Error(`payout checkout submit failed: HTTP ${submit.status} ${submit.json?.code ?? ''}`);
  const returnPath = await approve(approvalUrl);
  const returned = await api('GET', returnPath, { timeoutMs: 60_000 });
  if (returned.status !== 200 || returned.json?.state !== 'PAID' || returned.json?.paymentState !== 'CAPTURED') {
    throw new Error(`payout checkout capture failed: HTTP ${returned.status} state=${returned.json?.state ?? 'none'}`);
  }
  const state = dbState(item.checkoutId);
  if (state.journals !== 1) throw new Error(`capture journal count ${state.journals}, expected 1`);
  return { checkoutId: item.checkoutId, totalWon: state.amountMinor, seller: catalog.sellerId };
}

async function payoutProfile(sellerId, seller, verifier, id) {
  const configured = await api('PUT', `/api/sellers/${sellerId}/payout-profile`, {
    token: seller.accessToken, headers: { 'Idempotency-Key': `${RUN}-profile-${id}` },
    body: { countryCode: 'US', usdWalletConfirmed: true, verifiedAccountEmailConfirmed: true },
  });
  const profile = expect(configured, 200, 'payout profile configure');
  const verified = await api('POST', `/api/internal/settlement/payout-profiles/${sellerId}/verify`, {
    token: verifier.accessToken, headers: { 'Idempotency-Key': `${RUN}-verify-${id}` },
    body: { profileVersion: profile.profileVersion, evidenceReference: `EXP102-${id}` },
  });
  return expect(verified, 200, 'payout profile verify');
}

async function payoutCycle(catalog, seller, verifier, approver, cycle, expectedItemStatus) {
  const buyer = await account('payout-buyer', cycle);
  const checkout = await createPayoutCheckout(catalog, buyer, cycle);
  const profile = await api('GET', `/api/sellers/${catalog.sellerId}/payout-profile`, { token: seller.accessToken });
  if (profile.status === 404 || profile.json?.state !== 'ACTIVE') {
    await payoutProfile(catalog.sellerId, seller, verifier, cycle);
  }
  const periodStart = new Date(Date.now() - 86_400_000 * (cycle + 2)).toISOString();
  const periodEnd = new Date(Date.now() - 86_400_000 * (cycle + 1)).toISOString();
  const requested = expect(await api('POST', `/api/sellers/${catalog.sellerId}/payouts?periodStart=${encodeURIComponent(periodStart)}&periodEnd=${encodeURIComponent(periodEnd)}`, {
    token: seller.accessToken, headers: { 'Idempotency-Key': `${RUN}-payout-${cycle}` },
  }), 201, 'seller payout request');
  const payoutId = uuidOnly(requested.payoutId);
  const approvalKey = `${RUN}-approve-${cycle}`;
  expect(await api('POST', `/api/internal/settlement/payouts/${payoutId}/approve`, {
    token: approver.accessToken, headers: { 'Idempotency-Key': approvalKey }, body: { reason: `EXP102-${cycle}` },
  }), 202, 'operator payout approval');
  const deadline = Date.now() + 180_000;
  let dispatch = null;
  let payout = null;
  while (Date.now() < deadline) {
    payout = (await api('GET', `/api/sellers/${catalog.sellerId}/payouts/${payoutId}`, { token: seller.accessToken })).json;
    dispatch = (await api('GET', `/api/internal/settlement/payouts/${payoutId}/dispatch`, { token: approver.accessToken })).json;
    if (['CONFIRMED', 'UNCLAIMED', 'NEEDS_REVIEW', 'FAILED'].includes(payout?.state)) break;
    await sleep(1_000);
  }
  if (!payout || !dispatch) throw new Error(`payout ${cycle}: no result before bounded polling deadline`);
  // 같은 Idempotency-Key로 승인을 다시 보낸 뒤 워커가 한 바퀴 이상 돈 다음에 상태를 읽어, 재생이 항목을 늘리지 않는지 본다.
  const batchBeforeReplay = sql(`SELECT coalesce(provider_batch_id, '') FROM settlement_payout_dispatch_outbox WHERE payout_id='${payoutId}'`);
  const replay = await api('POST', `/api/internal/settlement/payouts/${payoutId}/approve`, {
    token: approver.accessToken, headers: { 'Idempotency-Key': approvalKey }, body: { reason: `EXP102-${cycle}` },
  });
  await sleep(4_000);
  const db = sql(`SELECT o.provider_batch_id, o.provider_item_id, o.sender_item_id, o.provider_item_status, o.provider_batch_status, o.provider_amount_minor, o.provider_fee_minor, o.provider_fee_currency, o.provider_fee_won, p.state, (SELECT count(*) FROM settlement_journal j WHERE j.source_payout_id=p.payout_id), (SELECT count(*) FROM settlement_entry e JOIN settlement_journal j USING (journal_id) WHERE j.source_payout_id=p.payout_id), COALESCE((SELECT sum(e.amount_won) FROM settlement_entry e JOIN settlement_journal j USING (journal_id) WHERE j.source_payout_id=p.payout_id AND e.entry_side='DEBIT'), 0), COALESCE((SELECT sum(e.amount_won) FROM settlement_entry e JOIN settlement_journal j USING (journal_id) WHERE j.source_payout_id=p.payout_id AND e.entry_side='CREDIT'), 0), (SELECT count(*) FROM settlement_payout_operator_action a WHERE a.payout_id=p.payout_id AND a.action='PAYOUT_APPROVED'), (SELECT count(*) FROM settlement_payout_dispatch_outbox x WHERE x.payout_id=p.payout_id) FROM settlement_payout p JOIN settlement_payout_dispatch_outbox o USING (payout_id) WHERE p.payout_id='${payoutId}'`);
  const fields = db.split('\t');
  const [batchId, itemId, senderItemId, itemStatus, batchStatus, amountMinor, feeMinor, feeCurrency, feeWon, payoutState, journalCount, journalLines, debitWon, creditWon, approvalCount, outboxCount] = fields;
  const journalBalanced = Number(journalLines) > 0 && Number(debitWon) === Number(creditWon);
  let truth = null;
  if (batchId) {
    const batch = await paypalDirect(`/v1/payments/payouts/${encodeURIComponent(batchId)}?page=1&page_size=100&total_required=true`);
    const item = (batch.items ?? []).find((candidate) => candidate.payout_item_id === itemId)
      ?? (batch.items ?? []).find((candidate) => candidate.payout_item?.sender_item_id === senderItemId);
    truth = {
      topKeys: Object.keys(batch).sort(),
      batchStatus: batch.batch_header?.batch_status ?? null,
      items: batch.items?.length ?? 0,
      itemStatus: item?.transaction_status ?? null,
      amount: item?.payout_item?.amount?.value ?? null,
      currency: item?.payout_item?.amount?.currency ?? null,
      fee: item?.payout_item_fee?.value ?? null,
      feeCurrency: item?.payout_item_fee?.currency ?? null,
      senderItemMatches: item?.payout_item?.sender_item_id === senderItemId,
    };
  }
  const balance = expect(await api('GET', `/api/sellers/${catalog.sellerId}/payouts/balance`, { token: seller.accessToken }), 200, 'payout balance');
  const ok = expectedItemStatus === 'SUCCESS'
    ? payoutState === 'CONFIRMED' && itemStatus === 'SUCCESS' && Number(journalCount) === 1 && journalBalanced
      && Number(balance.pendingPayoutAmountWon) === 0 && Number(approvalCount) === 1 && Number(outboxCount) === 1 && replay.status === 202 && batchBeforeReplay === batchId
      && truth?.itemStatus === 'SUCCESS' && truth.items === 1 && truth.senderItemMatches
      && truth.amount === (Number(amountMinor) / 100).toFixed(2) && truth.currency === 'USD'
      && truth.fee === (Number(feeMinor) / 100).toFixed(2) && truth.feeCurrency === 'USD'
      && feeCurrency === 'USD'
    : payoutState === 'UNCLAIMED' && itemStatus === 'UNCLAIMED' && Number(journalCount) === 0
      && truth?.itemStatus === 'UNCLAIMED' && truth.items === 1
      && Number(outboxCount) === 1 && Number(approvalCount) === 1 && replay.status === 202 && batchBeforeReplay === batchId
      && Number(balance.pendingPayoutAmountWon) >= Number(requested.amountWon);
  const result = {
    cycle, sellerId: catalog.sellerId, checkoutId: checkout.checkoutId,
    payoutState, itemStatus, batchStatus, journalCount: Number(journalCount), approvalCount: Number(approvalCount),
    outboxCount: Number(outboxCount), journalLines: Number(journalLines), debitWon: Number(debitWon), creditWon: Number(creditWon),
    journalBalanced, amountMinor: Number(amountMinor), feeMinor: feeMinor ? Number(feeMinor) : null,
    feeCurrency, feeWon: feeWon ? Number(feeWon) : null, replayStatus: replay.status,
    reservedWon: Number(balance.pendingPayoutAmountWon), truth, ok,
  };
  if (!ok) throw new Error(`payout cycle invariant failed: ${JSON.stringify(result)}`);
  console.log(JSON.stringify({ payoutCycle: result }));
  return result;
}

async function loginThrottleProbe() {
  // H7: 틀린 비밀번호 n=30을 보낸 뒤 올바른 비밀번호가 거절되는지, 실패 카운터가 한도에서 멈추는지 본다.
  const user = await account('throttle', 0);
  const statuses = [];
  const slowCounts = { beforeLimit: 0, afterLimit: 0 };
  for (let attempt = 1; attempt <= 30; attempt += 1) {
    const response = await api('POST', '/api/auth/login', { body: { email: user.email, password: `wrong-${attempt}-${RUN}` } });
    statuses.push(response.status);
    if (response.ms >= 30) slowCounts[attempt <= 10 ? 'beforeLimit' : 'afterLimit'] += 1;
  }
  const correct = await api('POST', '/api/auth/login', { body: { email: user.email, password: ACCOUNT_PASSWORD } });
  const failures = sql(`SELECT max(failures) FROM auth_subject_bucket WHERE scope='LOGIN'`);
  const result = {
    wrongAttempts: statuses.length, status401: statuses.filter((code) => code === 401).length,
    status429: statuses.filter((code) => code === 429).length, otherStatus: statuses.filter((code) => code !== 401 && code !== 429).length,
    slowFirst10: slowCounts.beforeLimit, slowAfter10: slowCounts.afterLimit,
    correctPasswordAfterLimitStatus: correct.status, failureCounter: Number(failures),
  };
  console.log(JSON.stringify({ loginThrottle: result }));
  return result;
}

async function payoutMain() {
  try {
    await verifyProxyTopology();
    await clearToxics();
    await clearToxics(CAPTURE_PROXY);
    const sellerEmail = envLocal.PAYPAL_SANDBOX_BUYER_EMAIL;
    const successCatalog = await setupCatalog(sellerEmail, `${RUN}-success-sku`);
    const successSeller = successCatalog.seller;
    const verifier = await operator('verifier');
    const approver = await operator('approver');
    const successful = [];
    for (let cycle = 0; cycle < 3; cycle += 1) {
      successful.push(await payoutCycle(successCatalog, successSeller, verifier, approver, cycle, 'SUCCESS'));
    }
    const unclaimed = [];
    for (let cycle = 0; cycle < 2; cycle += 1) {
      const catalog = await setupCatalog(`${RUN}-unclaimed-${cycle}@example.test`, `${RUN}-unclaimed-sku-${cycle}`);
      const seller = catalog.seller;
      await unclaimed.push(await payoutCycle(catalog, seller, verifier, approver, 10 + cycle, 'UNCLAIMED'));
    }
    if (successful.length !== 3 || successful.some((item) => !item.ok) || unclaimed.length !== 2 || unclaimed.some((item) => !item.ok)) {
      throw new Error('payout E2E expected 3 SUCCESS and 2 UNCLAIMED terminal cases');
    }
    await loginThrottleProbe();
    console.log(JSON.stringify({ payoutE2E: { status: 'verified', success: successful.length, unclaimed: unclaimed.length } }));
  } finally {
    await browser?.close();
  }
}

async function main() {
  if (PAYOUT_E2E) return payoutMain();
  await verifyProxyTopology();
  await clearToxics();
  await clearToxics(CAPTURE_PROXY);
  const catalog = await setupCatalog();
  const buyers = [];
  for (let i = 0; i < REPEAT; i += 1) buyers.push(await account('buyer', i));
  const counter = { n: 0 };
  const report = {
    run: RUN, startedAt: new Date().toISOString(), repeat: REPEAT, unitPriceWon: UNIT_PRICE_WON,
    fx: 'SYNTHETIC_FIXTURE 1400 KRW/USD', recoveryWorkerEnabled: RECOVERY_WORKER_ENABLED, conditions: [],
  };
  try {
    for (const cond of CONDITIONS) {
      if (only && !only.has(cond.name)) continue;
      const result = await runCondition(cond, catalog, buyers, counter);
      report.conditions.push(result);
      console.log(JSON.stringify({
        condition: result.condition,
        catalog: result.catalog,
        warmups: result.warmups,
        checkouts: result.checkouts.map((c) => ({
          submit: c.submit,
          return: c.return,
          converged: c.converged,
          convergeMs: c.convergeMs,
          captureFaultObserved: c.captureFaultObserved,
          manualReconcile: c.manualReconcile,
          atlasAtReturn: c.atlasAtReturn,
          atlas: c.atlas.checkout,
          paypalCapturesAtReturn: c.paypalAtReturn.completedCaptures,
          paypalCaptures: c.paypal.completedCaptures,
          invariants: Object.values(c.invariants).every(Boolean),
        })),
      }));
    }
  } finally {
    await Promise.all([...activeCatalogStops].map((stopCatalog) => stopCatalog().catch(() => {})));
    await clearToxics().catch(() => {});
    await clearToxics(CAPTURE_PROXY).catch(() => {});
    await browser?.close();
    mkdirSync(RESULTS_DIR, { recursive: true });
    writeFileSync(`${RESULTS_DIR}/${RUN}.json`, `${JSON.stringify(report, null, 2)}\n`);
    console.log(`결과: ${RESULTS_DIR}/${RUN}.json`);
  }
  const violations = report.conditions.flatMap((condition) => condition.checkouts)
    .filter((checkout) => !checkout.return || checkout.return.status !== 200
        || (!report.recoveryWorkerEnabled
            && (!checkout.manualReconcile || checkout.manualReconcile.status !== 200
                || checkout.manualReconcile.checkoutState !== 'PAID'
                || checkout.manualReconcile.paymentState !== 'CAPTURED'))
        || checkout.captureFaultObserved === false
        || !checkout.converged || !Object.values(checkout.invariants).every(Boolean));
  if (violations.length) {
    console.error(`검증 실패 ${violations.length}건 (callback/reconcile, 수렴 또는 불변식 위반)`);
    process.exitCode = 1;
  }
}

main().catch((error) => { console.error(error.message); process.exitCode = 1; });
