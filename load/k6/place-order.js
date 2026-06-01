// Day 20 — Load test: place-order flow (write-heavy, IO-bound).
//
// ĐÂY là workload chính để (a) đo P50/95/99 + throughput + error rate, và
// (b) so VT vs platform thread under load (chạy 2 lần đổi RUN_PROFILE +
// SPRING_PROFILES_ACTIVE phía order-service).
//
// ⚠️ MÔ HÌNH MỞ (open model) — `ramping-arrival-rate`:
//   k6 BƠM request theo TỐC ĐỘ cố định (X req/s), KHÔNG chờ response trước
//   khi gửi cái tiếp theo. Khi hệ thống chậm lại, request DỒN Ứ → P99 phơi
//   bày đúng độ đau. Closed model (`ramping-vus`: mỗi VU loop tuần tự) sẽ
//   TỰ ĐỘNG giảm tải khi server chậm → giấu tail latency (coordinated
//   omission). Senior luôn dùng open model để đo capacity. Xem
//   docs/lessons/20-load-testing-methodology.md.
//
// Flow mỗi iteration: add-to-cart → place-order. Cart-add nằm trong vòng đo
// vì đó là path thật user đi; nhưng ta TAG riêng từng bước để tách latency.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { cfg, authHeaders, runProfile } from './lib/config.js';
import { buildTokenPool } from './lib/auth.js';
import { orderThresholds } from './thresholds.js';

// Custom metric: tách latency từng bước để trace timeline trong report.
const placeOrderLatency = new Trend('place_order_latency', true);
const cartAddLatency = new Trend('cart_add_latency', true);
const oversoldRejections = new Counter('order_insufficient_stock');

export const options = {
  // Stage: warmup 30s → ramp lên 200 req/s trong 1m → giữ 2m → ramp 0.
  // Chỉnh `target` để tìm knee point (RPS mà P99 bắt đầu vỡ threshold).
  scenarios: {
    place_order: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      // preAllocatedVUs: pool VU k6 giữ sẵn. Phải đủ lớn để arrival-rate
      // không bị nghẽn bởi thiếu VU (k6 cảnh báo "insufficient VUs" =
      // chính k6 thành bottleneck, số đo vô nghĩa). Rule: ≥ target_rate ×
      // expected_latency_giây × hệ-số-an-toàn.
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: [
        { target: 50, duration: '30s' },   // warmup (JIT, pool fill, cache)
        { target: 200, duration: '1m' },   // ramp tới tải mục tiêu
        { target: 200, duration: '2m' },   // steady — đây là cửa sổ đo chính
        { target: 0, duration: '30s' },    // ramp down
      ],
    },
  },
  thresholds: orderThresholds,
  // Tag toàn run bằng profile để Grafana/summary phân biệt vt vs platform.
  tags: { profile: runProfile },
};

export function setup() {
  return { tokens: buildTokenPool() };
}

export default function (data) {
  // Round-robin token theo VU id → phân tán qua pool user (tránh hot-row).
  const token = data.tokens[__VU % data.tokens.length];
  const opts = authHeaders(token);

  // 1) Add-to-cart (HINCRBY atomic phía cart-service, Day 5).
  const cartRes = http.post(
    `${cfg.cart}/cart/items`,
    JSON.stringify({ sku: cfg.sku, quantity: 1 }),
    Object.assign({}, opts, { tags: { step: 'cart_add' } }),
  );
  cartAddLatency.add(cartRes.timings.duration);
  const cartOk = check(cartRes, { 'cart add 200': (r) => r.status === 200 });
  if (!cartOk) return; // không có cart thì place-order vô nghĩa, bỏ iteration

  // 2) Place-order. idempotencyKey unique mỗi lần → đo đúng path tạo mới
  //    (KHÔNG đo nhầm fast-path idempotent return của Day 6).
  const orderRes = http.post(
    `${cfg.order}/orders`,
    JSON.stringify({
      currency: cfg.currency,
      idempotencyKey: uuidv4(),
      shipping: {
        recipient: 'Load Test',
        phone: '0900000000',
        line: '1 Test Street',
        city: 'HCMC',
        countryCode: 'VN',
      },
    }),
    Object.assign({}, opts, { tags: { step: 'place_order' } }),
  );
  placeOrderLatency.add(orderRes.timings.duration);

  const ok = check(orderRes, {
    'order 200': (r) => r.status === 200,
    'has orderId': (r) => !!r.json('data.id'),
  });

  // Hết stock = 409 hợp lệ (không tính lỗi hệ thống) — đếm riêng để biết
  // có cần seed thêm inventory không, đừng để nó thổi phồng error rate.
  if (orderRes.status === 409) oversoldRejections.add(1);
  if (!ok && orderRes.status !== 409) {
    // Log mẫu 1% lỗi thật để điều tra, tránh spam.
    if (Math.random() < 0.01) console.error(`order fail: ${orderRes.status} ${orderRes.body}`);
  }
}

export function teardown(data) {
  // Best-effort: không cleanup user (pool tái dùng giữa các run cho nhanh).
  console.log(`teardown: profile=${runProfile}, pool=${data.tokens.length} user`);
}
