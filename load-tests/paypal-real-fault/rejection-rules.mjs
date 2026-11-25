// EXP-094: PayPal 확정 거절 규칙(ADR-0079 A2~A5)을 실제 Sandbox에서 검증하는 하네스.
//
// 실행: REAL_SANDBOX_OPT_IN=YES PHASES=orders,auth,hunt,mock node rejection-rules.mjs
//   PHASES  : orders(주문 생성 거절), auth(void된 authorization의 capture·void), hunt(음성 사냥), mock(만료 모의)
//   SEED    : 조건 순서를 섞는 시드(기본 94)    REPS: 주문 생성 변이별 반복(기본 3)
//   AUTH_N  : auth 단계 authorization 수(기본 8)  RESULTS_DIR: 결과 폴더(기본 /tmp/atlas-h4-results)
// 자격 증명은 저장소 루트 .env.local(별도 ENV_FILE)에서 프로세스 안으로만 읽고 출력·저장하지 않는다.
// api-m.sandbox.paypal.com 만 호출한다. 결과 파일의 ID는 가린다.
import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import os from 'node:os';

const ROOT = new URL('../../', import.meta.url).pathname;
const OUT = process.env.RESULTS_DIR || '/tmp/atlas-h4-results';
const RAW = process.env.RAW_DIR || '/tmp/h4_raw';
const PHASES = (process.env.PHASES || 'orders,auth,hunt,mock').split(',');
const REPS = Number(process.env.REPS || 3);
const AUTH_N = Number(process.env.AUTH_N || 8);
const SEED = Number(process.env.SEED || 94);
const RETURN_ORIGIN = 'http://127.0.0.1:18981';
const SANDBOX = 'https://api-m.sandbox.paypal.com';
const RUN = Date.now().toString(36);

if (process.env.REAL_SANDBOX_OPT_IN !== 'YES') throw new Error('REAL_SANDBOX_OPT_IN=YES 가 필요하다(실제 Sandbox 호출).');
const env = Object.fromEntries(readFileSync(process.env.ENV_FILE || `${ROOT}.env.local`, 'utf8').split('\n')
  .filter((l) => /^[A-Z_]+=/.test(l)).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
for (const k of ['PAYPAL_CLIENT_ID', 'PAYPAL_CLIENT_SECRET', 'PAYPAL_SANDBOX_BUYER_EMAIL', 'PAYPAL_SANDBOX_BUYER_PASSWORD']) {
  if (!env[k]) throw new Error(`.env.local 에 ${k} 가 없다`);
}
if (env.PAYPAL_BASE_URL && env.PAYPAL_BASE_URL.replace(/\/$/, '') !== SANDBOX) throw new Error('PAYPAL_BASE_URL 이 Sandbox 가 아니다');

const log = (...xs) => console.error(new Date().toISOString().slice(11, 19), ...xs);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 고정 시드 난수와 섞기: 조건 순서를 시드로 재현 가능하게 섞는다.
function rng(seed) { let a = seed >>> 0; return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
const random = rng(SEED);
const shuffle = (xs) => { const a = [...xs]; for (let i = a.length - 1; i > 0; i -= 1) { const j = Math.floor(random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };

// ----- 가리기 -----
const idMap = new Map();
const short = (s) => { if (!idMap.has(s)) idMap.set(s, createHash('sha1').update(RUN + s).digest('hex').slice(0, 4)); return idMap.get(s); };
const ID_RE = /\b[0-9A-Z]{17}\b/g;
function redactString(s) { return s.replace(ID_RE, (m) => `ID-${short(m)}`).replace(/[^\s"']+@[^\s"']+/g, 'buyer@example.invalid'); }
function redact(v, key = '') {
  if (v == null) return v;
  if (typeof v === 'string') {
    if (key === 'debug_id') return 'debug-redacted';
    if (['payer_id', 'merchant_id', 'account_id'].includes(key)) return 'ID-redacted';
    if (['given_name', 'surname'].includes(key)) return 'Test';
    return redactString(v);
  }
  if (Array.isArray(v)) return v.map((x) => redact(x, key));
  if (typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, redact(x, k)]));
  return v;
}

// ----- HTTP -----
let token = null; let tokenAt = 0;
async function accessToken() {
  if (token && Date.now() - tokenAt < 5 * 60_000) return token;
  const basic = Buffer.from(`${env.PAYPAL_CLIENT_ID}:${env.PAYPAL_CLIENT_SECRET}`).toString('base64');
  const res = await fetch(`${SANDBOX}/v1/oauth2/token`, {
    method: 'POST', headers: { Authorization: `Basic ${basic}`, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=client_credentials', signal: AbortSignal.timeout(60_000),
  });
  if (res.status !== 200) throw new Error(`token 요청 실패: ${res.status}`);
  token = (await res.json()).access_token; tokenAt = Date.now();
  return token;
}
const rawLog = [];
async function api(method, path, { body, headers = {}, label = '' } = {}) {
  const started = Date.now();
  const res = await fetch(`${SANDBOX}${path}`, {
    method,
    headers: { Authorization: `Bearer ${await accessToken()}`, ...(method === 'POST' || method === 'PATCH' ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: method === 'POST' || method === 'PATCH' ? (body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body))) : undefined,
    signal: AbortSignal.timeout(60_000),
  });
  const text = await res.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* 본문 없음 또는 JSON 아님 */ }
  const out = { status: res.status, json, ms: Date.now() - started, contentType: res.headers.get('content-type') };
  rawLog.push({ label, method, path: redactString(path), status: out.status, body: redact(json) });
  return out;
}
// 응답의 요약: 상태, 오류 이름, 이슈 목록(순서 유지), 본문 키 집합, id 유무
const sum = (r) => ({
  http: r.status, name: r.json?.name ?? null, issues: (r.json?.details || []).map((d) => d.issue ?? null),
  keys: r.json && typeof r.json === 'object' ? Object.keys(r.json).sort() : null,
  hasId: Boolean(r.json?.id), hasApproveLink: Boolean((r.json?.links || []).find((l) => ['approve', 'payer-action'].includes(l.rel))),
  status: r.json?.status ?? null,
});
const isTransient = (r) => r.status === 429 || r.status >= 500;

// ----- 구매자 승인(헤드리스) -----
let browser = null;
async function approve(approvalUrl) {
  if (!browser) browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ locale: 'en-US' });
  const page = await context.newPage();
  let returned = false;
  await page.route(`${RETURN_ORIGIN}/**`, async (route) => { returned = true; await route.fulfill({ status: 204, body: '' }); });
  page.setDefaultTimeout(15_000);
  try {
    await page.goto(approvalUrl, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await page.locator('#email').fill(env.PAYPAL_SANDBOX_BUYER_EMAIL);
    await page.locator('#btnNext').click();
    await page.locator('#password').fill(env.PAYPAL_SANDBOX_BUYER_PASSWORD);
    await page.locator('#btnLogin').click();
    await page.locator('[data-testid="submit-button-initial"]').click({ timeout: 45_000 });
    const deadline = Date.now() + 45_000;
    while (!returned && Date.now() < deadline) await sleep(250);
    return returned;
  } catch (error) {
    return { error: String(error.message).split('\n')[0].slice(0, 160) };
  } finally { await context.close(); }
}

// ----- 주문·authorization 준비 -----
const unitBody = (extra = {}) => ({
  intent: 'AUTHORIZE',
  purchase_units: [{ reference_id: 'h4', amount: { currency_code: 'USD', value: '1.00' }, ...extra }],
  payment_source: { paypal: { experience_context: { return_url: `${RETURN_ORIGIN}/return`, cancel_url: `${RETURN_ORIGIN}/cancel`, user_action: 'PAY_NOW' } } },
});
const approvalOf = (json) => (json?.links || []).find((l) => l.rel === 'payer-action' || l.rel === 'approve')?.href;
let seq = 0;
const rid = (tag) => `h4-${RUN}-${tag}-${(seq += 1)}`;

async function newAuthorization(tag, amount = '1.00') {
  const created = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': rid(`${tag}c`) }, label: `${tag}:create`,
    body: { ...unitBody({ custom_id: `h4-${tag}` }), purchase_units: [{ reference_id: 'h4', custom_id: `h4-${tag}`, amount: { currency_code: 'USD', value: amount } }] } });
  if (!created.json?.id) return { error: `create ${created.status}`, transient: isTransient(created) };
  const approved = await approve(approvalOf(created.json));
  if (approved !== true) return { error: 'approve', detail: approved };
  const az = await api('POST', `/v2/checkout/orders/${created.json.id}/authorize`, { headers: { 'PayPal-Request-Id': rid(`${tag}a`) }, body: {}, label: `${tag}:authorize` });
  const auth = az.json?.purchase_units?.[0]?.payments?.authorizations?.[0];
  if (!auth?.id) return { error: `authorize ${az.status}`, issues: sum(az).issues, transient: isTransient(az) };
  return { orderId: created.json.id, authId: auth.id, authStatus: auth.status, expirationTime: auth.expiration_time, createTime: auth.create_time };
}
const getOrder = async (orderId, label) => {
  const r = await api('GET', `/v2/checkout/orders/${orderId}`, { label });
  const pu = r.json?.purchase_units?.[0];
  return { http: r.status, orderStatus: r.json?.status, customId: pu?.custom_id ?? null,
    authStatuses: (pu?.payments?.authorizations || []).map((a) => a.status), captureCount: (pu?.payments?.captures || []).length,
    captureStatuses: (pu?.payments?.captures || []).map((c) => c.status), authExpiration: pu?.payments?.authorizations?.[0]?.expiration_time ?? null };
};
const capture = (authId, { amount, final, requestId, prefer = true, mock, label } = {}) => api('POST', `/v2/payments/authorizations/${authId}/capture`, {
  headers: { 'PayPal-Request-Id': requestId, ...(prefer ? { Prefer: 'return=representation' } : {}), ...(mock ? { 'PayPal-Mock-Response': JSON.stringify({ mock_application_codes: mock }) } : {}) },
  body: amount ? { amount: { currency_code: 'USD', value: amount }, final_capture: final ?? true } : {}, label });
const voidAuth = (authId, { requestId, mock, label } = {}) => api('POST', `/v2/payments/authorizations/${authId}/void`, {
  headers: { 'PayPal-Request-Id': requestId, ...(mock ? { 'PayPal-Mock-Response': JSON.stringify({ mock_application_codes: mock }) } : {}) }, body: undefined, label });

const results = { run: RUN, seed: SEED, startedAt: new Date().toISOString(), loadBefore: os.loadavg(), phases: {}, harnessErrors: [] };

// =====================================================================
// 단계 1: 주문 생성 거절(H1, H1b, H5)
// =====================================================================
const TRIGGERS = {
  T01: { desc: '통화 KRW(미지원)', body: (c) => unitBody({ custom_id: c, amount: { currency_code: 'KRW', value: '1000' } }) },
  T02: { desc: 'JPY에 소수 값', body: (c) => unitBody({ custom_id: c, amount: { currency_code: 'JPY', value: '1.50' } }) },
  T03: { desc: '금액 0.00', body: (c) => unitBody({ custom_id: c, amount: { currency_code: 'USD', value: '0.00' } }) },
  T04: { desc: '금액 -1.00', body: (c) => unitBody({ custom_id: c, amount: { currency_code: 'USD', value: '-1.00' } }) },
  T05: { desc: '소수 3자리 1.001', body: (c) => unitBody({ custom_id: c, amount: { currency_code: 'USD', value: '1.001' } }) },
  T06: { desc: '금액 문자열 abc', body: (c) => unitBody({ custom_id: c, amount: { currency_code: 'USD', value: 'abc' } }) },
  T07: { desc: 'amount 누락', body: (c) => ({ ...unitBody(), purchase_units: [{ reference_id: 'h4', custom_id: c }] }) },
  T08: { desc: 'intent 열거 외 STEAL', body: (c) => ({ ...unitBody({ custom_id: c }), intent: 'STEAL' }) },
  T09: { desc: 'purchase_units 빈 배열', body: () => ({ ...unitBody(), purchase_units: [] }) },
  T10: { desc: '통화 코드 ZZZ', body: (c) => unitBody({ custom_id: c, amount: { currency_code: 'ZZZ', value: '1.00' } }) },
  T11: { desc: '금액 한도 초과', body: (c) => unitBody({ custom_id: c, amount: { currency_code: 'USD', value: '99999999999.00' } }) },
  T12: { desc: 'custom_id 256자 초과', body: () => unitBody({ custom_id: 'x'.repeat(300) }) },
  T13: { desc: 'invoice_id 128자 초과', body: (c) => unitBody({ custom_id: c, invoice_id: 'i'.repeat(200) }) },
  T14: { desc: 'items 합계와 breakdown 불일치', body: (c) => ({ ...unitBody(), purchase_units: [{ reference_id: 'h4', custom_id: c,
    amount: { currency_code: 'USD', value: '5.00', breakdown: { item_total: { currency_code: 'USD', value: '9.00' } } },
    items: [{ name: 'x', quantity: '1', unit_amount: { currency_code: 'USD', value: '1.00' } }] }] }) },
  T15: { desc: 'PayPal-Mock-Response DUPLICATE_INVOICE_ID(모의)', mock: 'DUPLICATE_INVOICE_ID', body: (c) => unitBody({ custom_id: c }) },
};
// T12·T09 는 custom_id 로 교정 판별을 못 하므로(본문에 custom_id 없음 또는 같은 값) 판별은 id 유무로만 한다.
const goodBody = (c) => unitBody({ custom_id: c });

async function phaseOrders() {
  const rows = [];
  const plan = shuffle(Object.keys(TRIGGERS).flatMap((t) => Array.from({ length: REPS }, (_, i) => ({ t, rep: i + 1 }))));
  // 변경 없음 대조(앞): 정상 본문
  const control0 = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': rid('ctl0') }, body: goodBody('h4-ctl0'), label: 'control:create-start' });
  results.controlStart = sum(control0);
  for (const { t, rep } of plan) {
    const spec = TRIGGERS[t];
    const requestId = rid(`${t}r${rep}`);
    const badMarker = `h4-bad-${t}-${rep}`;
    const first = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': requestId, ...(spec.mock ? { 'PayPal-Mock-Response': JSON.stringify({ mock_application_codes: spec.mock }) } : {}) },
      body: spec.body(badMarker), label: `${t}:first` });
    const row = { trigger: t, rep, desc: spec.desc, first: sum(first), transient: isTransient(first) };
    if (first.status >= 400 && first.status < 500 && !isTransient(first)) {
      // H1b-1: 같은 Request-Id, 같은 본문 재전송
      const same = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': requestId, ...(spec.mock ? { 'PayPal-Mock-Response': JSON.stringify({ mock_application_codes: spec.mock }) } : {}) }, body: spec.body(badMarker), label: `${t}:same-rid-same-body` });
      row.sameRequestSameBody = sum(same);
      // H1: 같은 Request-Id, 교정 본문(모의 헤더 없음): 돌아온 주문의 custom_id 가 거절 본문의 값인지
      const fixMarker = `h4-fix-${t}-${rep}`;
      const fix = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': requestId }, body: goodBody(fixMarker), label: `${t}:same-rid-fixed-body` });
      row.sameRequestFixedBody = sum(fix);
      if (fix.json?.id) {
        const g = await getOrder(fix.json.id, `${t}:get-fixed`);
        row.fixedOrderCustomId = g.customId === fixMarker ? 'fix-marker' : (g.customId === badMarker ? 'BAD-MARKER(반례)' : String(g.customId));
      }
    }
    rows.push(row);
  }
  // 실제 중복 invoice_id: 같은 invoice_id 로 주문 두 건(Request-Id 다름)
  const inv = `h4-inv-${RUN}`;
  const dup1 = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': rid('dup1') }, body: unitBody({ custom_id: 'h4-dup1', invoice_id: inv }), label: 'dup:first' });
  const dup2 = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': rid('dup2') }, body: unitBody({ custom_id: 'h4-dup2', invoice_id: inv }), label: 'dup:second' });
  results.realDuplicateInvoice = { first: sum(dup1), second: sum(dup2) };
  // 음성 대조 (a): 첫 호출이 성공한 Request-Id 로 교정 본문 재전송 → 첫 본문의 custom_id 가 돌아와야(판별기 반응)
  const okR = rid('negA');
  const ok1 = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': okR }, body: goodBody('h4-neg-first'), label: 'negA:first' });
  const ok2 = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': okR }, body: goodBody('h4-neg-second'), label: 'negA:replay' });
  const g2 = ok2.json?.id ? await getOrder(ok2.json.id, 'negA:get') : null;
  results.negativeControlDiscriminator = { first: sum(ok1), replay: sum(ok2), sameOrderId: Boolean(ok1.json?.id) && ok1.json?.id === ok2.json?.id,
    replayCustomId: g2?.customId ?? null, discriminatorFires: g2?.customId === 'h4-neg-first' };
  // H5: 기타 4xx 본문 이슈 유무(401, 404, 다중 위반)
  const unknownOrder = await api('GET', '/v2/checkout/orders/0AAAAAAAAAAAAAAAA', { label: 'h5:get-unknown-order' });
  const bad = await fetch(`${SANDBOX}/v2/checkout/orders/0AAAAAAAAAAAAAAAA`, { headers: { Authorization: 'Bearer invalid' }, signal: AbortSignal.timeout(60_000) });
  let badJson = null; try { badJson = await bad.json(); } catch { /* */ }
  const multi = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': rid('multi') }, label: 'h5:multi',
    body: { ...unitBody(), intent: 'STEAL', purchase_units: [{ reference_id: 'h4', amount: { currency_code: 'KRW', value: 'abc' } }] } });
  const unknownAuthCapture = await capture('0AAAAAAAAAAAAAAAA', { requestId: rid('unk'), amount: '1.00', label: 'h5:capture-unknown-auth' });
  results.otherFourXX = { getUnknownOrder: sum(unknownOrder), invalidBearer: { http: bad.status, name: badJson?.name ?? badJson?.error ?? null, hasDetails: Boolean(badJson?.details) },
    multiViolation: sum(multi), captureUnknownAuthorization: sum(unknownAuthCapture) };
  // 변경 없음 대조(뒤)
  const control1 = await api('POST', '/v2/checkout/orders', { headers: { 'PayPal-Request-Id': rid('ctl1') }, body: goodBody('h4-ctl1'), label: 'control:create-end' });
  results.controlEnd = sum(control1);
  results.phases.orders = { rows };
}

// =====================================================================
// 단계 2: void된 authorization 의 capture / void 반복 (H2, H3)
// =====================================================================
const CAPTURE_VARIANTS = [
  { name: 'full+prefer', opts: { amount: '1.00', prefer: true } },
  { name: 'partial0.50', opts: { amount: '0.50', final: false, prefer: true } },
  { name: 'full-no-prefer', opts: { amount: '1.00', prefer: false } },
  { name: 'empty-body', opts: { prefer: true } },
];
async function phaseAuth() {
  const rows = [];
  const order = shuffle(Array.from({ length: AUTH_N }, (_, i) => i));
  for (const i of order) {
    const row = { instance: i + 1, variantsTried: [] };
    const a = await newAuthorization(`au${i}`);
    if (a.error) { row.harnessError = a; results.harnessErrors.push({ phase: 'auth', i, ...a }); rows.push(row); continue; }
    row.authorize = { status: a.authStatus, expiresInHours: Math.round((Date.parse(a.expirationTime) - Date.parse(a.createTime)) / 36e5 * 100) / 100 };
    const firstVoidRid = rid(`vd${i}`);
    const v1 = await voidAuth(a.authId, { requestId: firstVoidRid, label: `au${i}:void` });
    row.void = sum(v1);
    const beforeGet = await getOrder(a.orderId, `au${i}:get-after-void`);
    row.getAfterVoid = beforeGet;
    // capture 변이 2개씩(인스턴스마다 다른 두 개를 돌려 모든 변이가 n>=4 가 되게 한다)
    const picks = [CAPTURE_VARIANTS[i % 4], CAPTURE_VARIANTS[(i + 1) % 4]];
    for (const v of picks) {
      const reqId = rid(`cp${i}${v.name}`);
      const c1 = await capture(a.authId, { ...v.opts, requestId: reqId, label: `au${i}:capture:${v.name}` });
      const g = await getOrder(a.orderId, `au${i}:get-after-capture:${v.name}`);
      const t = { variant: v.name, capture: sum(c1), getAfter: g };
      if (v.name === 'full+prefer' || v.name === 'partial0.50') {
        const c2 = await capture(a.authId, { ...v.opts, requestId: reqId, label: `au${i}:capture-retry-same-rid:${v.name}` });
        t.retrySameRequestId = sum(c2);
      }
      row.variantsTried.push(t);
    }
    // H3: void 반복(새 Request-Id 두 번, 같은 Request-Id 한 번)
    const vAgain1 = await voidAuth(a.authId, { requestId: rid(`vd${i}x`), label: `au${i}:void-again-1` });
    const vAgain2 = await voidAuth(a.authId, { requestId: rid(`vd${i}y`), label: `au${i}:void-again-2` });
    const vSame = await voidAuth(a.authId, { requestId: firstVoidRid, label: `au${i}:void-same-rid-replay` });
    row.voidAgain = [sum(vAgain1), sum(vAgain2)];
    row.voidSameRequestIdReplay = sum(vSame);
    // 직접 authorization 조회(Payments v2)
    const ga = await api('GET', `/v2/payments/authorizations/${a.authId}`, { label: `au${i}:get-auth` });
    row.getAuthorization = { http: ga.status, status: ga.json?.status ?? null, hasExpiration: Boolean(ga.json?.expiration_time) };
    rows.push(row);
    log(`auth ${i + 1}/${AUTH_N} done`);
  }
  results.phases.auth = { rows };
}

// =====================================================================
// 단계 3: 음성 사냥 (H4)
// =====================================================================
async function phaseHunt() {
  const out = {};
  // 1·2: capture 두 번 → 두 번째 ALREADY_CAPTURED, capture 뒤 void → PREVIOUSLY_CAPTURED
  {
    const a = await newAuthorization('hu1');
    if (a.error) { results.harnessErrors.push({ phase: 'hunt1', ...a }); } else {
      const r1 = rid('hu1c1');
      const c1 = await capture(a.authId, { amount: '1.00', requestId: r1, label: 'hunt1:capture1' });
      const g1 = await getOrder(a.orderId, 'hunt1:get-1');
      const c2 = await capture(a.authId, { amount: '1.00', requestId: rid('hu1c2'), label: 'hunt1:capture2-new-rid' });
      const g2 = await getOrder(a.orderId, 'hunt1:get-2');
      const c1r = await capture(a.authId, { amount: '1.00', requestId: r1, label: 'hunt1:capture1-replay' });
      const v = await voidAuth(a.authId, { requestId: rid('hu1v'), label: 'hunt1:void-after-capture' });
      const g3 = await getOrder(a.orderId, 'hunt1:get-3');
      const az2 = await api('POST', `/v2/checkout/orders/${a.orderId}/authorize`, { headers: { 'PayPal-Request-Id': rid('hu1a2') }, body: {}, label: 'hunt1:authorize-again-after-capture' });
      out.captureThenRepeat = { capture1: sum(c1), getAfter1: g1, capture2NewRequestId: sum(c2), getAfter2: g2, capture1Replay: sum(c1r), voidAfterCapture: sum(v), getAfterVoid: g3, authorizeAgainAfterCapture: sum(az2) };
    }
  }
  // 3: 이미 authorize 된 주문에 authorize 한 번 더
  {
    const a = await newAuthorization('hu3');
    if (a.error) { results.harnessErrors.push({ phase: 'hunt3', ...a }); } else {
      const az2 = await api('POST', `/v2/checkout/orders/${a.orderId}/authorize`, { headers: { 'PayPal-Request-Id': rid('hu3a2') }, body: {}, label: 'hunt3:authorize-again' });
      const g = await getOrder(a.orderId, 'hunt3:get');
      await voidAuth(a.authId, { requestId: rid('hu3v'), label: 'hunt3:cleanup-void' });
      out.authorizeTwice = { second: sum(az2), getAfter: g };
    }
  }
  // 4: 일부 capture → 남은 부분 void → 처음 capture 와 같은 Request-Id 로 재시도
  {
    const a = await newAuthorization('hu4');
    if (a.error) { results.harnessErrors.push({ phase: 'hunt4', ...a }); } else {
      const r1 = rid('hu4c1');
      const c1 = await capture(a.authId, { amount: '0.50', final: false, requestId: r1, label: 'hunt4:partial-capture' });
      const v = await voidAuth(a.authId, { requestId: rid('hu4v'), label: 'hunt4:void-remaining' });
      const g1 = await getOrder(a.orderId, 'hunt4:get-after-void');
      const retry = await capture(a.authId, { amount: '0.50', final: false, requestId: r1, label: 'hunt4:retry-same-rid' });
      const g2 = await getOrder(a.orderId, 'hunt4:get-after-retry');
      const fresh = await capture(a.authId, { amount: '0.50', final: false, requestId: rid('hu4c2'), label: 'hunt4:new-rid' });
      const g3 = await getOrder(a.orderId, 'hunt4:get-after-new');
      out.partialCaptureThenVoid = { partialCapture: sum(c1), voidRemaining: sum(v), getAfterVoid: g1, retrySameRequestId: sum(retry), getAfterRetry: g2, newRequestId: sum(fresh), getAfterNew: g3 };
    }
  }
  // 5: capture 와 void 동시 호출(레이스) 3회
  out.races = [];
  for (let k = 0; k < 3; k += 1) {
    const a = await newAuthorization(`hr${k}`);
    if (a.error) { results.harnessErrors.push({ phase: `hunt-race${k}`, ...a }); continue; }
    const [c, v] = await Promise.all([
      capture(a.authId, { amount: '1.00', requestId: rid(`hr${k}c`), label: `race${k}:capture` }),
      voidAuth(a.authId, { requestId: rid(`hr${k}v`), label: `race${k}:void` }),
    ]);
    const g = await getOrder(a.orderId, `race${k}:get`);
    out.races.push({ capture: sum(c), void: sum(v), getAfter: g });
  }
  results.phases.hunt = out;
}

// =====================================================================
// 단계 4: PayPal-Mock-Response 로 만료 시뮬레이션 (H6)
// =====================================================================
async function phaseMock() {
  const out = [];
  for (let k = 0; k < 2; k += 1) {
    const a = await newAuthorization(`mk${k}`);
    if (a.error) { results.harnessErrors.push({ phase: `mock${k}`, ...a }); continue; }
    const row = { instance: k + 1, authorizeStatus: a.authStatus };
    for (const code of ['AUTHORIZATION_EXPIRED', 'AUTHORIZATION_VOIDED', 'PREVIOUSLY_VOIDED', 'AUTHORIZATION_ALREADY_CAPTURED', 'INSTRUMENT_DECLINED']) {
      const m = await capture(a.authId, { amount: '1.00', requestId: rid(`mk${k}${code.slice(0, 6)}`), mock: code, label: `mock${k}:capture:${code}` });
      row[`captureMock_${code}`] = { ...sum(m), mockMessage: m.json?.message ?? null };
    }
    row.voidMock = sum(await voidAuth(a.authId, { requestId: rid(`mk${k}v`), mock: 'PREVIOUSLY_VOIDED', label: `mock${k}:void:PREVIOUSLY_VOIDED` }));
    const g = await getOrder(a.orderId, `mock${k}:get-after-mocks`);
    row.getAfterMocks = g;
    const real = await capture(a.authId, { amount: '1.00', requestId: rid(`mk${k}real`), label: `mock${k}:capture-real` });
    row.captureWithoutMock = sum(real);
    out.push(row);
  }
  results.phases.mock = out;
}

try {
  for (const p of PHASES) {
    log('phase', p);
    if (p === 'orders') await phaseOrders();
    else if (p === 'auth') await phaseAuth();
    else if (p === 'hunt') await phaseHunt();
    else if (p === 'mock') await phaseMock();
  }
} finally {
  if (browser) await browser.close();
  results.finishedAt = new Date().toISOString();
  results.loadAfter = os.loadavg();
  mkdirSync(OUT, { recursive: true }); mkdirSync(RAW, { recursive: true });
  writeFileSync(`${OUT}/rejection-rules-${RUN}.json`, JSON.stringify(results, null, 2));
  writeFileSync(`${RAW}/rejection-rules-raw-${RUN}.json`, JSON.stringify(rawLog, null, 2));
  log('wrote', `${OUT}/rejection-rules-${RUN}.json`);
}
