import http from 'k6/http';
import { sleep } from 'k6';

const ACCOUNT_PASSWORD = __ENV.K6_TEST_ACCOUNT_PASSWORD || '';
const MAIL_API_URL = (__ENV.K6_MAIL_API_URL || '').replace(/\/$/, '');
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

function verificationTokenFor(email) {
  if (!MAIL_API_URL) throw new Error('K6_MAIL_API_URL is required for email verification');
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const query = encodeURIComponent(`to:${email}`);
    const search = http.get(`${MAIL_API_URL}/api/v1/search?query=${query}&limit=10`, {
      tags: { phase: 'setup', operation: 'email-verification-search' },
    });
    if (search.status === 200) {
      const summary = search.json();
      const message = (summary.messages || summary.Messages || [])[0];
      const messageId = message && (message.ID || message.id);
      if (messageId) {
        const detail = http.get(`${MAIL_API_URL}/api/v1/message/${encodeURIComponent(messageId)}`, {
          tags: { phase: 'setup', operation: 'email-verification-read' },
        });
        if (detail.status === 200) {
          const body = detail.json();
          const text = body.Text || body.text || '';
          const match = text.match(/one-time token:\s*(\S+)/i);
          if (match) return match[1];
        }
      }
    }
    sleep(1);
  }
  throw new Error(`No verification message received for ${email}`);
}

export function registerBuyer(baseUrl, runId, role, index) {
  const email = emailFor(runId, role, index);
  const response = http.post(`${baseUrl}/api/auth/signup`, JSON.stringify({
    email,
    password: requireAccountPassword(),
  }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { phase: 'setup', operation: 'account-signup' },
  });
  if (response.status !== 202) throw new Error(`account signup failed: HTTP ${response.status}`);
  const token = verificationTokenFor(email);
  const verification = http.post(`${baseUrl}/api/auth/verify-email`, JSON.stringify({ token }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { phase: 'setup', operation: 'email-verification' },
  });
  if (verification.status !== 204) throw new Error(`account verification failed: HTTP ${verification.status}`);
  return loginBuyer(baseUrl, runId, role, index);
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
