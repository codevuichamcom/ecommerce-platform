// Day 20 — Load test: browse-products flow (read-heavy, IO-bound, cache-hot).
//
// Mục đích KÉP:
//   1. Regression guard cho 2-tier cache (Day 15) + GIN index search (Day 16)
//      — P95 < 80ms nhờ Caffeine L1 / Redis L2.
//   2. Workload IO-bound thuần để VT toả sáng: mỗi request block ngắn chờ
//      DB/Redis, hàng ngàn request đồng thời. Đây là kịch bản VT thắng platform
//      RÕ NHẤT (xem 20b-vt-vs-platform). Place-order cũng IO-bound nhưng vướng
//      connection pool write → ít tách bạch hơn.
//
// Open model y hệt place-order để số liệu so sánh được.

import http from 'k6/http';
import { check } from 'k6';
import { cfg, runProfile } from './lib/config.js';
import { browseThresholds } from './thresholds.js';

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
      stages: [
        { target: 200, duration: '30s' },
        { target: 1000, duration: '1m' },  // read path chịu tải cao hơn nhiều
        { target: 1000, duration: '2m' },
        { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds: browseThresholds,
  tags: { profile: runProfile },
};

// Vài keyword để tránh cache 100% 1 key (thực tế tìm kiếm đa dạng).
const keywords = ['ao', 'quan', 'giay', 'tui', 'mu', 'phone', 'laptop'];

export default function () {
  const kw = keywords[Math.floor(Math.random() * keywords.length)];
  const page = Math.floor(Math.random() * 5); // page 0-4 (deep page Day 18 riêng)

  const res = http.get(
    `${cfg.product}/products?keyword=${kw}&page=${page}&size=20`,
    { tags: { step: 'browse' } },
  );

  check(res, {
    'browse 200': (r) => r.status === 200,
    'has content': (r) => Array.isArray(r.json('data.content')),
  });
}
