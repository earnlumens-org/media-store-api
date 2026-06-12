// Scenario 4 — Checkout pipeline: prepare → submit (202) → polling
// (SCALABILITY-AUDIT.md, task 3.4).
//
// Validates Phase 2 (task 2.2): the submit path must return fast (202 +
// PROCESSING) because on-chain confirmation happens on background virtual
// threads — request threads must not block on Horizon polling. The audit
// exit criterion is p99 < 2 s for submit.
//
// ── How it runs ─────────────────────────────────────────────────────────
// k6 cannot sign Stellar transactions, so the FULL pipeline needs a signer
// sidecar: SIGNER_URL points to a local helper that receives
// {unsignedXdr, networkPassphrase}, signs with a funded TEST wallet
// (testnet/staging only!) and returns {signedXdr}. A reference
// implementation is provided in tools/xdr-signer.mjs.
//
//   node tools/xdr-signer.mjs &       # SIGNER_SECRET=S... (test wallet)
//   k6 run checkout.js -e BASE_URL=https://staging... -e AUTH_TOKEN=... \
//     -e ENTRY_ID=<paid entry id> -e BUYER_WALLET=G... -e SIGNER_URL=http://localhost:8787
//
// Without SIGNER_URL the scenario degrades gracefully and still measures the
// two server-side hot paths that matter for thread-pool sizing:
//   prepare (order creation + Horizon account checks + tx building) and
//   order-status polling (the post-202 read path) — submit is skipped.
//
// ⚠ NEVER run against production with a real wallet: every iteration creates
//   a real PENDING order and a real on-chain payment if signed. Use staging
//   + Stellar testnet, per the README ground rules.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { BASE_URL, AUTH_TOKEN, authHeaders, constantRate } from './lib/config.js';

const ENTRY_ID = __ENV.ENTRY_ID || '';
const COLLECTION_ID = __ENV.COLLECTION_ID || '';
const BUYER_WALLET = __ENV.BUYER_WALLET || '';
const SIGNER_URL = __ENV.SIGNER_URL || '';
/** Max seconds to poll the order after a 202 before giving up. */
const POLL_TIMEOUT_S = parseInt(__ENV.POLL_TIMEOUT_S || '90', 10);

const prepareDuration = new Trend('checkout_prepare_duration', true);
const submitDuration = new Trend('checkout_submit_duration', true);
const confirmDuration = new Trend('checkout_confirm_duration', true); // 202 → final state
const submitAccepted = new Rate('checkout_submit_202');
const orderCompleted = new Rate('checkout_order_completed');

export const options = {
  scenarios: { checkout: constantRate({ rate: Math.min(2, parseInt(__ENV.RPS || '1', 10)) }) },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    checkout_prepare_duration: ['p(99)<3000'],
    // Audit exit criterion (task 2.2): submit p99 < 2 s — it no longer
    // waits for on-chain confirmation.
    checkout_submit_duration: ['p(99)<2000'],
    'http_req_duration{name:order-status}': ['p(99)<500'],
  },
};

export function setup() {
  if (!AUTH_TOKEN) throw new Error('AUTH_TOKEN is required (buyer session)');
  if (!ENTRY_ID && !COLLECTION_ID) throw new Error('ENTRY_ID or COLLECTION_ID is required (a PAID item)');
  if (!BUYER_WALLET) throw new Error('BUYER_WALLET is required (G... public key of the test wallet)');
  if (!SIGNER_URL) {
    console.warn('SIGNER_URL not set — measuring prepare + polling only, submit is skipped.');
  }
}

const jsonHeaders = () => Object.assign({ 'Content-Type': 'application/json' }, authHeaders());

export default function () {
  // 1 ── prepare: server builds the tx, creates the PENDING order
  const prepareRes = http.post(`${BASE_URL}/api/payments/prepare`, JSON.stringify({
    entryId: ENTRY_ID || null,
    collectionId: COLLECTION_ID || null,
    franchiseSlug: null,
    buyerWallet: BUYER_WALLET,
  }), { headers: jsonHeaders(), tags: { name: 'prepare' } });

  prepareDuration.add(prepareRes.timings.duration);
  // 409 = an order for this item is already in flight for this buyer —
  // expected under repetition with a single test account.
  if (!check(prepareRes, { 'prepare 200/409': (r) => r.status === 200 || r.status === 409 })) return;
  if (prepareRes.status !== 200) { sleep(1); return; }

  const order = prepareRes.json();

  if (!SIGNER_URL) {
    // Degraded mode: exercise the polling read path against the PENDING order.
    http.get(`${BASE_URL}/api/payments/orders/${order.orderId}`,
        { headers: authHeaders(), tags: { name: 'order-status' } });
    return;
  }

  // 2 ── sign via the local sidecar (test wallet, staging/testnet only)
  const signRes = http.post(SIGNER_URL, JSON.stringify({
    unsignedXdr: order.unsignedXdr,
    networkPassphrase: order.networkPassphrase,
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'sign' } });
  if (!check(signRes, { 'signed': (r) => r.status === 200 })) return;

  // 3 ── submit: must come back fast (202 PROCESSING in async mode)
  const submitRes = http.post(`${BASE_URL}/api/payments/submit`, JSON.stringify({
    orderId: order.orderId,
    signedXdr: signRes.json().signedXdr,
  }), { headers: jsonHeaders(), tags: { name: 'submit' } });

  submitDuration.add(submitRes.timings.duration);
  submitAccepted.add(submitRes.status === 202);
  if (!check(submitRes, { 'submit 200/202': (r) => r.status === 200 || r.status === 202 })) return;

  // 4 ── poll the order until a final state, like the SPA does after a 202
  const started = Date.now();
  let status = submitRes.json().status;
  while (status === 'PROCESSING' && (Date.now() - started) / 1000 < POLL_TIMEOUT_S) {
    sleep(3);
    const poll = http.get(`${BASE_URL}/api/payments/orders/${order.orderId}`,
        { headers: authHeaders(), tags: { name: 'order-status' } });
    if (poll.status === 200) status = poll.json().status;
  }

  confirmDuration.add(Date.now() - started);
  orderCompleted.add(status === 'COMPLETED');
}
