// Day 20 — Base config cho k6 load test.
//
// Mọi giá trị env-driven để 1 script chạy được trên local / CI / staging mà
// KHÔNG sửa code. k6 đọc env qua `__ENV`. Chạy:
//   k6 run -e BASE_ORDER=http://localhost:8086 load/k6/place-order.js
//
// KHÔNG hard-code token/secret ở đây — token sinh runtime trong setup().

export const cfg = {
  auth: __ENV.BASE_AUTH || 'http://localhost:8081',
  cart: __ENV.BASE_CART || 'http://localhost:8085',
  order: __ENV.BASE_ORDER || 'http://localhost:8086',
  product: __ENV.BASE_PRODUCT || 'http://localhost:8082',

  // SKU + inventory phải được seed TRƯỚC khi chạy (xem load/README.md §Precondition).
  // Day 16 seed 1M product; chọn 1 SKU có stock đủ lớn cho toàn bộ run.
  sku: __ENV.SKU || 'SKU-LOADTEST-0001',
  currency: __ENV.CURRENCY || 'VND',

  // Mật khẩu chung cho pool user ảo sinh trong setup(). Chỉ dùng cho test.
  seedPassword: __ENV.SEED_PASSWORD || 'LoadTest-2026!pw',

  // Số user ảo pre-register trong setup(). VU pick-round-robin token từ pool
  // này — tránh 1 user duy nhất tạo hot-row contention giả tạo (Day 4/19).
  userPoolSize: parseInt(__ENV.USER_POOL || '50', 10),
};

/** Header chuẩn cho request đã auth. */
export function authHeaders(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  };
}

/** Profile đang đo — chỉ để gắn tag vào metric, phân biệt run vt vs platform. */
export const runProfile = __ENV.RUN_PROFILE || 'vt';
