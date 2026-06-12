// Shared configuration and helpers for the EarnLumens k6 load test suite.
// See loadtest/README.md for usage. (SCALABILITY-AUDIT.md — Phase 3, task 3.4)

/** Tenant origin under test, e.g. https://earnlumens.org or https://acme.earnlumens.org */
export const BASE_URL = (__ENV.BASE_URL || 'https://app-dev.earnlumens.org').replace(/\/$/, '');

/**
 * Optional JWT access token for authenticated scenarios (feed-authenticated,
 * checkout, hls). Mint one by logging in on the target environment and
 * copying the Bearer token the SPA sends. Keep it out of shell history:
 *   export AUTH_TOKEN="$(cat token.txt)"
 */
export const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';

/** Requests per second per scenario — keep within the agreed budget. */
export const RPS = parseInt(__ENV.RPS || '10', 10);

/** Test duration per scenario. */
export const DURATION = __ENV.DURATION || '2m';

/** Pre-allocated VUs for the constant-arrival-rate executors. */
export const VUS = parseInt(__ENV.VUS || '20', 10);

export function authHeaders() {
  if (!AUTH_TOKEN) return {};
  return { Authorization: `Bearer ${AUTH_TOKEN}` };
}

/**
 * Standard scenario shape: open-model constant arrival rate, so response
 * slowdowns do not silently reduce the offered load (closed-model VU loops
 * under-report saturation).
 */
export function constantRate(extra = {}) {
  return Object.assign({
    executor: 'constant-arrival-rate',
    rate: RPS,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: VUS,
    maxVUs: VUS * 4,
  }, extra);
}

/** Audit exit criterion: p99 < 500 ms on public reads. */
export const PUBLIC_READ_THRESHOLDS = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<300', 'p(99)<500'],
};
