// Day 20 — Threshold dùng chung (gate fail-the-build).
//
// Triết lý: load test KHÔNG có pass/fail tự động = chỉ là "đồ thị đẹp để
// ngắm". Threshold biến nó thành GATE. k6 exit code != 0 khi vi phạm →
// CI chặn merge. Đây là điểm phân biệt với "chạy JMeter xem cho vui".
//
// Số dưới là SLO mục tiêu cho place-order trên local Docker — chỉnh theo
// môi trường thật. Đo P95 VÀ P99: P95 = trải nghiệm số đông, P99 = tail
// (1/100 request — ở 1M req/ngày là 10K user bị chậm).

export const orderThresholds = {
  // Tail latency: P95 < 200ms, P99 < 500ms. abortOnFail dừng sớm khi vỡ
  // hẳn (tiết kiệm thời gian + không spam DB lúc đã rõ là fail).
  http_req_duration: ['p(95)<200', 'p(99)<500'],

  // Error budget: < 1% request lỗi. http_req_failed = status >= 400 hoặc
  // network error. Vượt = backpressure/timeout đang xảy ra.
  http_req_failed: ['rate<0.01'],

  // Business check (đặt hàng thành công) phải > 99%.
  checks: ['rate>0.99'],
};

export const browseThresholds = {
  // Read-only path nhanh hơn nhiều — siết chặt hơn để bắt regression cache.
  http_req_duration: ['p(95)<80', 'p(99)<200'],
  http_req_failed: ['rate<0.005'],
  checks: ['rate>0.995'],
};
