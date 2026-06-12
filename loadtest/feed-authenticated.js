// Scenario 3 — Authenticated feed (SCALABILITY-AUDIT.md, task 3.4).
//
// Validates Phase 2 (task 2.3): authenticated feeds resolve the language
// filter from JWT claims — zero lookups to the users collection per request —
// and always bypass the edge (Authorization ⇒ private, no-store), so this
// scenario measures true origin capacity for logged-in browsing.
//
// Requires AUTH_TOKEN (see README). The token's contentLanguages claims
// determine the language filter the backend applies.
//
//   k6 run feed-authenticated.js -e BASE_URL=https://earnlumens.org -e AUTH_TOKEN=... -e RPS=20

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, AUTH_TOKEN, authHeaders, constantRate, PUBLIC_READ_THRESHOLDS } from './lib/config.js';

const edgeBypass = new Rate('edge_bypass');

export const options = {
  scenarios: { feed_authenticated: constantRate() },
  thresholds: Object.assign({
    // Authenticated responses must NEVER be served from the shared edge cache.
    edge_bypass: ['rate>0.99'],
  }, PUBLIC_READ_THRESHOLDS),
};

const PAGES = [
  '/public/entries/feed?sort=newest&page=0&size=48',
  '/public/entries/feed?sort=newest&page=1&size=48',
  '/public/entries/community/feed?sort=newest&page=0&size=48',
  '/public/entries/feed?type=AUDIO&sort=newest&page=0&size=48',
  '/public/entries/feed?pricing=FREE&sort=newest&page=0&size=48',
];

export function setup() {
  if (!AUTH_TOKEN) {
    throw new Error('AUTH_TOKEN is required for the authenticated feed scenario (see loadtest/README.md)');
  }
}

export default function () {
  const url = BASE_URL + PAGES[Math.floor(Math.random() * PAGES.length)];
  const res = http.get(url, { headers: authHeaders(), tags: { name: 'auth-feed' } });

  check(res, {
    'status 200': (r) => r.status === 200,
    'not publicly cacheable': (r) =>
      !(r.headers['Cache-Control'] || '').includes('public'),
  });

  edgeBypass.add((res.headers['X-Edge-Cache'] || '').toUpperCase() !== 'HIT');
}
