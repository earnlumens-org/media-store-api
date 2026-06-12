// Scenario 5 — HLS playback via cdn-worker (SCALABILITY-AUDIT.md, task 3.4).
//
// Validates Phase 1 (task 1.3) and the cdn-worker delivery path: playlists
// (1 h edge TTL) and segments (7 d, immutable) must be served from the edge,
// with the entitlement check cached 30 min — so a popular stream generates
// almost zero origin/API load after warm-up.
//
// Uses the same-origin tenant-CDN path the SPA uses: <tenant>/cdn/media/...
// Entitlement requires an authenticated viewer that owns the entry (or a
// FREE/preview HLS entry); pass AUTH_TOKEN and ENTRY_ID accordingly.
//
//   k6 run hls.js -e BASE_URL=https://earnlumens.org -e AUTH_TOKEN=... \
//     -e ENTRY_ID=<hls entry id> -e RPS=30

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, AUTH_TOKEN, authHeaders, constantRate } from './lib/config.js';

const ENTRY_ID = __ENV.ENTRY_ID || '';
const PLAYLIST = __ENV.PLAYLIST || 'master.m3u8';
/** How many media segments to fetch per iteration (a player pulls them continuously). */
const SEGMENTS_PER_ITERATION = parseInt(__ENV.SEGMENTS_PER_ITERATION || '3', 10);

const segmentEdgeHit = new Rate('hls_segment_edge_hit');

export const options = {
  scenarios: { hls: constantRate() },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:hls-playlist}': ['p(99)<500'],
    'http_req_duration{name:hls-segment}': ['p(95)<200', 'p(99)<500'],
    // Warm segments are immutable and must come from the edge.
    hls_segment_edge_hit: ['rate>0.90'],
  },
};

export function setup() {
  if (!AUTH_TOKEN) throw new Error('AUTH_TOKEN is required (entitled viewer)');
  if (!ENTRY_ID) throw new Error('ENTRY_ID is required (an entry with HLS renditions)');
}

const base = () => `${BASE_URL}/cdn/media/${ENTRY_ID}/hls`;

export default function () {
  // 1 ── master playlist → variant playlist
  const master = http.get(`${base()}/${PLAYLIST}`,
      { headers: authHeaders(), tags: { name: 'hls-playlist' } });
  if (!check(master, { 'playlist 200': (r) => r.status === 200 })) return;

  const lines = master.body.split('\n').map((l) => l.trim());
  const variant = lines.find((l) => l && !l.startsWith('#') && l.endsWith('.m3u8'));

  let mediaPlaylist = master;
  if (variant) {
    mediaPlaylist = http.get(`${base()}/${variant}`,
        { headers: authHeaders(), tags: { name: 'hls-playlist' } });
    if (!check(mediaPlaylist, { 'variant 200': (r) => r.status === 200 })) return;
  }

  // 2 ── a window of media segments, like a player buffering
  const segments = mediaPlaylist.body.split('\n')
      .map((l) => l.trim())
      .filter((l) => l && !l.startsWith('#') && !l.endsWith('.m3u8'));
  if (segments.length === 0) return;

  const start = Math.floor(Math.random() * segments.length);
  const dir = variant ? variant.substring(0, variant.lastIndexOf('/') + 1) : '';
  for (let i = 0; i < SEGMENTS_PER_ITERATION; i++) {
    const seg = segments[(start + i) % segments.length];
    const res = http.get(`${base()}/${dir}${seg}`,
        { headers: authHeaders(), tags: { name: 'hls-segment' } });
    check(res, { 'segment 200/206': (r) => r.status === 200 || r.status === 206 });
    const cache = (res.headers['Cf-Cache-Status'] || res.headers['X-Edge-Cache'] || '').toUpperCase();
    if (cache) segmentEdgeHit.add(cache === 'HIT');
  }
}
