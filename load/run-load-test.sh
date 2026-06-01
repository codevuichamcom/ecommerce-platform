#!/usr/bin/env bash
# Day 20 — Orchestrate 1 lần load test reproducible.
#
# Làm 4 việc: (1) sanity-check service up, (2) warmup ngắn (JIT + fill pool),
# (3) chạy k6 với output summary JSON, (4) in path kết quả.
#
# KHÔNG tự start service/stack — giữ explicit để bạn kiểm soát profile JVM
# (vt vs platform). Xem load/README.md cho thứ tự chạy đầy đủ.
#
# Usage:
#   ./load/run-load-test.sh place-order vt
#   ./load/run-load-test.sh browse-products platform
set -euo pipefail

SCRIPT="${1:-place-order}"               # place-order | browse-products
PROFILE="${2:-vt}"                       # vt | platform (chỉ để tag + tên file)
K6_FILE="load/k6/${SCRIPT}.js"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="load/results"
SUMMARY="${OUT_DIR}/${SCRIPT}-${PROFILE}-${TS}.summary.json"

ORDER_URL="${BASE_ORDER:-http://localhost:8086}"

command -v k6 >/dev/null 2>&1 || { echo "❌ k6 chưa cài. https://k6.io/docs/get-started/installation/"; exit 1; }
mkdir -p "$OUT_DIR"

echo "🔎 Sanity-check order-service actuator..."
if ! curl -fsS "${ORDER_URL}/actuator/health" >/dev/null; then
  echo "❌ order-service không trả /actuator/health tại ${ORDER_URL}. Start service trước."
  exit 1
fi

echo "🔥 Warmup 15s (JIT compile + connection pool fill + cache prime)..."
# Warmup ép HotSpot compile hot path + Hikari mở connection — nếu không,
# 30s đầu của run chính dính cold-start, P99 bị thổi phồng oan.
k6 run --quiet --duration 15s --vus 10 \
  -e RUN_PROFILE="${PROFILE}-warmup" "$K6_FILE" >/dev/null 2>&1 || true

echo "🚀 Load test: ${SCRIPT} (profile=${PROFILE})"
k6 run \
  -e RUN_PROFILE="${PROFILE}" \
  --summary-export "$SUMMARY" \
  "$K6_FILE"

echo ""
echo "✅ Done. Summary JSON: ${SUMMARY}"
echo "   → Grafana dashboard: http://localhost:3000 (Load Test Overview)"
echo "   → Tempo traces:      http://localhost:3000/explore (datasource Tempo)"
