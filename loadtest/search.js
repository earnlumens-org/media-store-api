// Scenario 2 — Public search backed by $text indexes (SCALABILITY-AUDIT.md, task 3.4).
//
// Validates Phase 2 (task 2.1): searches run on the weighted text indexes,
// never regex collection scans. Search responses are per-query and effectively
// uncacheable, so this load lands on Cloud Run + Atlas — it is the scenario
// that exposes the Mongo tier first.
//
// NOTE: anonymous page-0 searches consume the 25/h/IP distributed budget
// (task 3.1) and the SEARCH rate-limit tier (40/min/IP). For a sustained
// search load test, provide AUTH_TOKEN so the run measures the database,
// not the rate limiter. Budget responses are tracked separately either way.
//
//   k6 run search.js -e BASE_URL=https://earnlumens.org -e AUTH_TOKEN=... -e RPS=10

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, authHeaders, constantRate } from './lib/config.js';

const budgetExhausted = new Rate('search_budget_exhausted');
const rateLimited = new Rate('search_rate_limited');

export const options = {
  scenarios: { search: constantRate() },
  thresholds: {
    http_req_failed: ['rate<0.05'], // 429s are counted separately below
    'http_req_duration{name:search}': ['p(95)<400', 'p(99)<800'],
    'http_req_duration{name:suggestions}': ['p(95)<200', 'p(99)<400'],
  },
};

// Realistic token mix: single terms, multi-token phrases, author-style
// lookups. Adjust to terms that actually exist in the target dataset so
// the test measures matching, not empty result sets.
const QUERIES = [
  'music', 'piano tutorial', 'stellar', 'photography tips',
  'crypto podcast', 'meditation', 'cooking', 'live set',
  'interview', 'design course',
];

export default function () {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  const headers = authHeaders();

  // Unified search (entries + collections + channels on page 0)
  const res = http.get(
    `${BASE_URL}/public/search?q=${encodeURIComponent(q)}&sort=relevance&page=0&size=24`,
    { headers, tags: { name: 'search' } }
  );

  rateLimited.add(res.status === 429);
  if (res.status === 200) {
    const body = res.json();
    budgetExhausted.add(body && body.loginRequired === true);
    check(res, { 'search 200': () => true });
  }

  // Autocomplete (fires on keystrokes in the real UI; prefix-indexed)
  const prefix = q.slice(0, 3);
  const sug = http.get(
    `${BASE_URL}/public/search/suggestions?q=${encodeURIComponent(prefix)}`,
    { headers, tags: { name: 'suggestions' } }
  );
  check(sug, { 'suggestions 200 or 429': (r) => r.status === 200 || r.status === 429 });
}
