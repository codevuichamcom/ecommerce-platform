// Day 20 — Auth helper cho k6 setup().
//
// Tách riêng để place-order.js / browse-products.js cùng dùng. Toàn bộ
// register + login chạy 1 LẦN trong setup() (k6 chạy setup tuần tự, ngoài
// vòng đo) — KHÔNG để login dính vào latency của workload chính, nếu không
// P99 sẽ bị nhiễu bởi BCrypt cost-10 (chậm có chủ đích, Day 2).

import http from 'k6/http';
import { check, fail } from 'k6';
import { cfg } from './config.js';

/**
 * Register (idempotent-ish: 409 duplicate email coi như OK vì pool tái dùng
 * giữa các run) rồi login, trả về JWT access token.
 */
export function registerAndLogin(email, password) {
  const body = JSON.stringify({ email, password });
  const headers = { headers: { 'Content-Type': 'application/json' } };

  const reg = http.post(`${cfg.auth}/auth/register`, body, headers);
  // 200/201 = tạo mới; 409 = đã tồn tại từ run trước → vẫn login được.
  if (reg.status !== 200 && reg.status !== 201 && reg.status !== 409) {
    fail(`register failed cho ${email}: ${reg.status} ${reg.body}`);
  }

  const login = http.post(`${cfg.auth}/auth/login`, body, headers);
  const ok = check(login, { 'login 200': (r) => r.status === 200 });
  if (!ok) fail(`login failed cho ${email}: ${login.status} ${login.body}`);

  const token = login.json('data.accessToken');
  if (!token) fail(`không lấy được accessToken cho ${email}`);
  return token;
}

/**
 * Tạo pool N user ảo, trả về mảng token. VU sẽ round-robin pool này để phân
 * tán tải qua nhiều user (tránh hot-row trên 1 user_id giả tạo).
 */
export function buildTokenPool() {
  const tokens = [];
  for (let i = 0; i < cfg.userPoolSize; i++) {
    const email = `loadtest+${i}@nexashop.test`;
    tokens.push(registerAndLogin(email, cfg.seedPassword));
  }
  console.log(`setup(): pre-authenticated ${tokens.length} user vào token pool`);
  return tokens;
}
