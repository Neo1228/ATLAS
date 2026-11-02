import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const profile = __ENV.GATE4_PROFILE || 'baseline';
const mode = __ENV.GATE4_MODE || 'load';
const runId = __ENV.GATE4_RUN_ID;
const baseUrl = __ENV.ATLAS_BASE_URL || 'http://atlas-gate4-app:8080';
const inventoryCapacity = profile === 'queue-backlog' ? 167 : 1667;
const maxActiveCheckouts = profile === 'queue-backlog' ? 1 : 1667;
const ticketTtlSeconds = 7200;
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || '1');

if (!['baseline', 'queue-backlog'].includes(profile)) {
    throw new Error(`Unsupported GATE4_PROFILE: ${profile}`);
}
if (!['pilot', 'load'].includes(mode)) {
    throw new Error(`Unsupported GATE4_MODE: ${mode}`);
}

if (!runId) {
    throw new Error('GATE4_RUN_ID is required');
}
if (mode === 'load' && (!Number.isInteger(preAllocatedVUs) || preAllocatedVUs < 1)) {
    throw new Error('PREALLOCATED_VUS must be a positive integer for load mode');
}

const startedIterations = new Counter('gate4_iterations_started');
const createdTickets = new Counter('gate4_tickets_created');
const waitingTickets = new Counter('gate4_tickets_waiting');
const expectedBacklog = new Counter('gate4_checkout_conflict_expected');
const inventoryUnavailable = new Counter('gate4_inventory_unavailable');
const paidSessions = new Counter('gate4_sessions_paid');
const unexpectedResponses = new Counter('gate4_unexpected_responses');
const buyerSessionDuration = new Trend('gate4_buyer_session_duration', true);

const scenario = mode === 'pilot'
    ? { executor: 'constant-vus', vus: 1, duration: '60s' }
    : {
        executor: 'constant-arrival-rate',
        rate: 10000,
        timeUnit: '1h',
        duration: '10m',
        preAllocatedVUs,
        maxVUs: preAllocatedVUs,
    };

export const options = {
    scenarios: { buyer_flow: scenario },
    tags: { gate4_run: runId, gate4_profile: profile, gate4_mode: mode },
};

const jsonHeaders = { 'Content-Type': 'application/json' };

export function setup() {
    const skuId = `gate4-${runId}`;
    const created = http.post(`${baseUrl}/api/campaigns`, JSON.stringify({
        skuId,
        inventoryCapacity,
        queueTicketTtlSeconds: ticketTtlSeconds,
        maxActiveCheckouts,
    }), { headers: jsonHeaders, tags: { stage: 'campaign_create' } });
    if (created.status !== 201) {
        throw new Error(`Campaign setup failed: status=${created.status}, body=${created.body}`);
    }

    const campaignId = JSON.parse(created.body).campaignId;
    const opened = http.post(`${baseUrl}/api/campaigns/${campaignId}/open`, null,
        { tags: { stage: 'campaign_open' } });
    if (opened.status !== 200) {
        throw new Error(`Campaign open failed: status=${opened.status}, body=${opened.body}`);
    }

    console.log(`GATE4_RUN=${runId} PROFILE=${profile} MODE=${mode} CAMPAIGN=${campaignId} `
        + `INVENTORY=${inventoryCapacity} K=${maxActiveCheckouts} TTL=${ticketTtlSeconds}`);
    return { campaignId, skuId };
}

export default function buyerFlow(data) {
    const startedAt = Date.now();
    startedIterations.add(1);

    const id = `${runId}-${__VU}-${__ITER}`;
    const joined = http.post(
        `${baseUrl}/api/campaigns/${data.campaignId}/tickets`,
        JSON.stringify({ skuId: data.skuId, buyerRef: `buyer-${id}` }),
        {
            headers: { ...jsonHeaders, 'Idempotency-Key': id },
            tags: { stage: 'queue_join' },
        },
    );
    if (joined.status !== 201) {
        unexpectedResponses.add(1, { stage: 'queue_join', status: String(joined.status) });
        buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'join_error' });
        check(joined, { 'queue join returns 201': (response) => response.status === 201 });
        return;
    }

    const ticket = JSON.parse(joined.body);
    createdTickets.add(1);
    if (ticket.status === 'WAITING') waitingTickets.add(1);

    const checkout = http.post(
        `${baseUrl}/api/campaigns/${data.campaignId}/tickets/${ticket.ticketId}/checkout`,
        null,
        { tags: { stage: 'checkout' } },
    );
    if (checkout.status === 409) {
        if (profile === 'queue-backlog') {
            expectedBacklog.add(1);
            buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'backlog' });
        } else {
            unexpectedResponses.add(1, { stage: 'checkout', status: '409' });
            buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'unexpected_conflict' });
        }
        check(checkout, {
            'checkout 409 only occurs in queue-backlog profile': () => profile === 'queue-backlog',
        });
        return;
    }
    if (checkout.status !== 200) {
        unexpectedResponses.add(1, { stage: 'checkout', status: String(checkout.status) });
        buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'checkout_error' });
        check(checkout, { 'checkout returns 200 or expected 409': (response) => response.status === 200 });
        return;
    }

    const order = JSON.parse(checkout.body);
    if (order.state === 'FAILED') {
        inventoryUnavailable.add(1);
        buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'inventory_unavailable' });
        return;
    }

    const payment = http.post(`${baseUrl}/api/orders/${order.orderId}/payment`, null,
        { tags: { stage: 'payment' } });
    if (payment.status !== 200) {
        unexpectedResponses.add(1, { stage: 'payment', status: String(payment.status) });
        buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'payment_error' });
        check(payment, { 'payment returns 200': (response) => response.status === 200 });
        return;
    }

    const result = JSON.parse(payment.body);
    if (result.state === 'PAID') {
        paidSessions.add(1);
        buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'paid' });
        check(payment, { 'payment reaches PAID': () => result.state === 'PAID' });
        return;
    }

    unexpectedResponses.add(1, { stage: 'payment', status: result.state || 'unknown' });
    buyerSessionDuration.add(Date.now() - startedAt, { outcome: 'unexpected_payment_state' });
    check(payment, { 'payment reaches PAID': () => result.state === 'PAID' });
}
