import http from 'k6/http';

const ACCOUNT_PASSWORD = __ENV.K6_TEST_ACCOUNT_PASSWORD || '';
const accountsByVu = new Map();

function requireAccountPassword() {
  if (ACCOUNT_PASSWORD.length < 12 || ACCOUNT_PASSWORD.length > 128) {
    throw new Error('K6_TEST_ACCOUNT_PASSWORD must be 12 to 128 characters');
  }
  return ACCOUNT_PASSWORD;
}

function emailFor(runId, role, index) {
  const safeRunId = runId.replace(/[^A-Za-z0-9-]/g, '-');
  return `k6-${safeRunId}-${role}-${index}@example.test`;
}

function tokenResponse(response, expectedStatus, operation) {
  if (response.status !== expectedStatus) {
    throw new Error(`${operation} failed: HTTP ${response.status}`);
  }
  let body;
  try {
    body = response.json();
  } catch (_) {
    throw new Error(`${operation} returned invalid JSON`);
  }
  if (!body.accessToken || !body.refreshToken || !body.buyerRef
      || !Number.isFinite(Number(body.expiresInSeconds))) {
    throw new Error(`${operation} returned an incomplete token response`);
  }
  return {
    accountId: body.accountId,
    buyerRef: body.buyerRef,
    sellerIds: body.sellerIds || [],
    accessToken: body.accessToken,
    refreshToken: body.refreshToken,
    tokenType: body.tokenType || 'Bearer',
    expiresAtMs: Date.now() + Number(body.expiresInSeconds) * 1000,
  };
}

export function registerBuyer(baseUrl, runId, role, index) {
  const response = http.post(`${baseUrl}/api/auth/signup`, JSON.stringify({
    email: emailFor(runId, role, index),
    password: requireAccountPassword(),
  }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { phase: 'setup', operation: 'account-signup' },
  });
  return tokenResponse(response, 201, 'account signup');
}

export function loginBuyer(baseUrl, runId, role, index) {
  const response = http.post(`${baseUrl}/api/auth/login`, JSON.stringify({
    email: emailFor(runId, role, index),
    password: requireAccountPassword(),
  }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { phase: 'setup', operation: 'account-login' },
  });
  return tokenResponse(response, 200, 'account login');
}

export function createSellerProfile(baseUrl, account) {
  const response = http.post(`${baseUrl}/api/sellers`, null, {
    headers: authHeaders(account.accessToken),
    tags: { phase: 'setup', operation: 'seller-onboarding' },
  });
  if (response.status !== 201) {
    throw new Error(`seller onboarding failed: HTTP ${response.status}`);
  }
  let body;
  try {
    body = response.json();
  } catch (_) {
    throw new Error('seller onboarding returned invalid JSON');
  }
  if (!body.sellerId) throw new Error('seller onboarding response is missing sellerId');
  return body.sellerId;
}

export function authHeaders(accessToken, extraHeaders = {}) {
  return {
    ...extraHeaders,
    Authorization: `Bearer ${accessToken}`,
  };
}

export function buyerForVu(baseUrl, buyers, vuId) {
  let account = accountsByVu.get(vuId);
  if (!account) {
    const seeded = buyers[vuId - 1];
    if (!seeded) throw new Error(`No authenticated Buyer was provisioned for VU ${vuId}`);
    account = { ...seeded };
  }

  if (Date.now() + 30_000 >= account.expiresAtMs) {
    const response = http.post(`${baseUrl}/api/auth/refresh`, JSON.stringify({
      refreshToken: account.refreshToken,
    }), {
      headers: { 'Content-Type': 'application/json' },
      tags: { phase: 'auth', operation: 'token-refresh' },
    });
    const refreshed = tokenResponse(response, 200, 'access token refresh');
    account = { ...account, ...refreshed };
  }

  accountsByVu.set(vuId, account);
  return account;
}
