// Scenario 1 — Anonymous public feed (SCALABILITY-AUDIT.md, task 3.4).
//
// Validates Phase 1: anonymous feed reads must be served mostly from the
// Cloudflare edge (tenants-router edge cache + Cache-Control from origin).
// Tracks the x-edge-cache header to compute the real edge hit rate under load.
//
//   k6 run feed-anonymous.js -e BASE_URL=https://earnlumens.org -e RPS=50

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, constantRate, PUBLIC_READ_THRESHOLDS } from './lib/config.js';

const edgeHit = new Rate('edge_cache_hit');

export const options = {
  scenarios: { feed_anonymous: constantRate() },
  thresholds: Object.assign({
    // Phase 1 exit criterion: >70 % of anonymous public reads from the edge.
    edge_cache_hit: ['rate>0.70'],
  }, PUBLIC_READ_THRESHOLDS),
};

// A small rotation of hot, cacheable feed pages — what a real anonymous
// browse session hits. Few distinct URLs (like production traffic) so the
// edge cache can do its job; page 0 dominates, deeper pages are rarer.
const PAGES = [
  '/public/entries?page=0&size=48',
  '/public/entries?page=0&size=48', // weight page 0 double
  '/public/entries?page=1&size=48',
  '/public/entries/feed?sort=newest&page=0&size=48',
  '/public/entries/community/feed?sort=newest&page=0&size=48',
  '/public/collections?page=0&size=48',
];

export default function () {
  const url = BASE_URL + PAGES[Math.floor(Math.random() * PAGES.length)];
  const res = http.get(url, { tags: { name: 'public-feed' } });

  check(res, {
    'status 200': (r) => r.status === 200,
    'is JSON': (r) => (r.headers['Content-Type'] || '').includes('application/json'),
  });

  const cache = (res.headers['X-Edge-Cache'] || '').toUpperCase();
  if (cache) edgeHit.add(cache === 'HIT');
}
