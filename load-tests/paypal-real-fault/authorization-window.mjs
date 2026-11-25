// PayPal Sandbox AUTHORIZE 만료 정보 관측 도구 (로컬 전용, 앱을 거치지 않고 PayPal REST를 직접 호출한다).
//
// 관측 대상: 승인된 order를 authorize했을 때 응답의 authorization.create_time/expiration_time,
// authorization 조회, honor period 안의 reauthorize 거절, void 뒤 capture 거절.
// 실행: REAL_SANDBOX_OPT_IN=YES node authorization-window.mjs  (REPEAT=3 기본)
// 자격 증명은 저장소 루트 .env.local(별도 worktree는 ENV_FILE)에서만 읽고 출력하지 않는다. api-m.sandbox.paypal.com만 호출한다.
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { chromium } from 'playwright';

const ROOT = new URL('../../', import.meta.url).pathname;
const OUT = process.env.RESULTS_DIR || '/tmp/atlas-c2-results';
const REPEAT = Number(process.env.REPEAT || 3);
const RETURN_ORIGIN = 'http://127.0.0.1:18981';
const SANDBOX = 'https://api-m.sandbox.paypal.com';

if (process.env.REAL_SANDBOX_OPT_IN !== 'YES') throw new Error('REAL_SANDBOX_OPT_IN=YES 가 필요하다');
if (!Number.isInteger(REPEAT) || REPEAT < 1 || REPEAT > 5) throw new Error('REPEAT 는 1-5 사이여야 한다');
const env = Object.fromEntries(readFileSync(process.env.ENV_FILE || `${ROOT}.env.local`, 'utf8').split('\n')
  .filter((l) => /^[A-Z_]+=/.test(l)).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
for (const k of ['PAYPAL_CLIENT_ID', 'PAYPAL_CLIENT_SECRET', 'PAYPAL_SANDBOX_BUYER_EMAIL', 'PAYPAL_SANDBOX_BUYER_PASSWORD']) {
  if (!env[k]) throw new Error(`.env.local 에 ${k} 가 없다`);
}
if (env.PAYPAL_BASE_URL !== SANDBOX) throw new Error('Sandbox 전용');

const log = (...xs) => console.error(new Date().toISOString().slice(11, 19), ...xs);

async function api(method, path, { token, body, headers = {} } = {}) {
  const res = await fetch(`${SANDBOX}${path}`, {
    method,
    headers: {
      ...(method === 'POST' ? { 'Content-Type': 'application/json' } : {}),
      Authorization: token ? `Bearer ${token}` : '',
      ...headers,
    },
    body: method === 'POST' ? JSON.stringify(body || {}) : undefined,
    signal: AbortSignal.timeout(60_000),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 본문 없음 또는 JSON 아님 */ }
  return { status: res.status, json };
}

async function accessToken() {
  const basic = Buffer.from(`${env.PAYPAL_CLIENT_ID}:${env.PAYPAL_CLIENT_SECRET}`).toString('base64');
  const res = await fetch(`${SANDBOX}/v1/oauth2/token`, {
    method: 'POST',
    headers: { Authorization: `Basic ${basic}`, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=client_credentials',
    signal: AbortSignal.timeout(60_000),
  });
  if (res.status !== 200) throw new Error(`token 요청 실패: ${res.status}`);
  return (await res.json()).access_token;
}

let browser;
async function approve(approvalUrl) {
  if (!browser) browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ locale: 'en-US' });
  const page = await context.newPage();
  let returned = false;
  await page.route(`${RETURN_ORIGIN}/**`, async (route) => {
    returned = true;
    await route.fulfill({ status: 204, body: '' });
  });
  page.setDefaultTimeout(15_000);
  try {
    await page.goto(approvalUrl, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await page.locator('#email').fill(env.PAYPAL_SANDBOX_BUYER_EMAIL);
    await page.locator('#btnNext').click();
    await page.locator('#password').fill(env.PAYPAL_SANDBOX_BUYER_PASSWORD);
    await page.locator('#btnLogin').click();
    await page.locator('[data-testid="submit-button-initial"]').click({ timeout: 45_000 });
    const deadline = Date.now() + 45_000;
    while (!returned && Date.now() < deadline) await new Promise((r) => setTimeout(r, 250));
    return returned;
  } finally {
    await context.close();
  }
}

const hours = (from, to) => Math.round(((Date.parse(to) - Date.parse(from)) / 3_600_000) * 100) / 100;
const issue = (r) => ({ http: r.status, name: r.json?.name, issues: (r.json?.details || []).map((d) => d.issue) });

const results = [];
const token = await accessToken();
try {
  for (let i = 0; i < REPEAT; i += 1) {
    const run = { n: i + 1 };
    const created = await api('POST', '/v2/checkout/orders', {
      token,
      headers: { 'PayPal-Request-Id': `c2-auth-window-${Date.now()}-${i}` },
      body: {
        intent: 'AUTHORIZE',
        purchase_units: [{ amount: { currency_code: 'USD', value: '1.00' }, custom_id: `c2-auth-window-${i}` }],
        payment_source: { paypal: { experience_context: {
          return_url: `${RETURN_ORIGIN}/return`, cancel_url: `${RETURN_ORIGIN}/cancel`, user_action: 'PAY_NOW' } } },
      },
    });
    run.createOrder = { http: created.status, status: created.json?.status };
    const orderId = created.json?.id;
    const approvalUrl = (created.json?.links || []).find((l) => l.rel === 'payer-action' || l.rel === 'approve')?.href;
    if (!orderId || !approvalUrl) { run.error = 'order 생성 또는 approval link 없음'; results.push(run); continue; }
    run.orderFields = Object.keys(created.json).sort();
    log('order created', i + 1);
    try {
      run.approved = await approve(approvalUrl);
    } catch (error) {
      run.approved = false;
      run.approveError = String(error.message).split('\n')[0].slice(0, 160);
      results.push(run);
      continue;
    }
    if (!run.approved) { results.push(run); continue; }
    const authorized = await api('POST', `/v2/checkout/orders/${orderId}/authorize`, {
      token, headers: { 'PayPal-Request-Id': `c2-auth-window-az-${Date.now()}-${i}`, Prefer: 'return=representation' },
    });
    const auth = authorized.json?.purchase_units?.[0]?.payments?.authorizations?.[0];
    run.authorize = { http: authorized.status, orderStatus: authorized.json?.status, authStatus: auth?.status,
      createTime: auth?.create_time, updateTime: auth?.update_time, expirationTime: auth?.expiration_time,
      expirationHoursAfterCreate: auth?.create_time && auth?.expiration_time ? hours(auth.create_time, auth.expiration_time) : null,
      linkRels: (auth?.links || []).map((l) => l.rel).sort(), authFields: auth ? Object.keys(auth).sort() : null };
    if (!auth?.id) { run.error = 'authorization 없음'; results.push(run); continue; }
    const fetched = await api('GET', `/v2/payments/authorizations/${auth.id}`, { token });
    run.get = { http: fetched.status, status: fetched.json?.status, expirationTime: fetched.json?.expiration_time,
      sameExpiration: fetched.json?.expiration_time === auth.expiration_time };
    const reauth = await api('POST', `/v2/payments/authorizations/${auth.id}/reauthorize`, {
      token, headers: { 'PayPal-Request-Id': `c2-auth-window-re-${Date.now()}-${i}` }, body: {},
    });
    run.reauthorizeInsideHonor = issue(reauth);
    const voidRequestId = `c2-auth-window-void-${Date.now()}-${i}`;
    const voided = await api('POST', `/v2/payments/authorizations/${auth.id}/void`, {
      token, headers: { 'PayPal-Request-Id': voidRequestId },
    });
    run.void = { http: voided.status };
    const after = await api('GET', `/v2/payments/authorizations/${auth.id}`, { token });
    run.getAfterVoid = { http: after.status, status: after.json?.status };
    const voidAgain = await api('POST', `/v2/payments/authorizations/${auth.id}/void`, { token });
    run.voidAgainNewRequestId = issue(voidAgain);
    const voidReplay = await api('POST', `/v2/payments/authorizations/${auth.id}/void`, {
      token, headers: { 'PayPal-Request-Id': voidRequestId },
    });
    run.voidReplaySameRequestId = issue(voidReplay);
    const captureAfterVoid = await api('POST', `/v2/payments/authorizations/${auth.id}/capture`, {
      token, headers: { 'PayPal-Request-Id': `c2-auth-window-cap-${Date.now()}-${i}` }, body: {},
    });
    run.captureAfterVoid = issue(captureAfterVoid);
    log('authorization observed', i + 1, run.authorize.authStatus, run.authorize.expirationHoursAfterCreate);
    results.push(run);
  }
} finally {
  if (browser) await browser.close();
}
mkdirSync(OUT, { recursive: true });
writeFileSync(`${OUT}/authorization-window.json`, JSON.stringify({ observedAt: new Date().toISOString(), results }, null, 2));
console.log(JSON.stringify(results, null, 1));
