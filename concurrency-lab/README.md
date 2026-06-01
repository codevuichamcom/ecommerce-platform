# concurrency-lab — Day 19

Sandbox đo concurrency primitives. **KHÔNG** phải service production — là nơi
chạy benchmark + demo để lấy số thật cho docs.

## Vì sao module riêng + `--enable-preview`

`StructuredTaskScope` là **preview API** ở Java 21. Bật `--enable-preview` đóng
dấu preview bit lên class → runtime cũng phải có cờ. Cô lập ở đây để KHÔNG ép
cờ này lên service production.

## Chạy gì

| Lệnh | Làm gì |
| --- | --- |
| `./gradlew :concurrency-lab:run` | JMH: lock throughput + VT vs platform thread |
| `./gradlew :concurrency-lab:run --args="Lock"` | Chỉ chạy `LockThroughputBenchmark` |
| `./gradlew :concurrency-lab:runPinningDemo` | Đếm `jdk.VirtualThreadPinned` event (synchronized vs ReentrantLock) |
| `./gradlew :concurrency-lab:test` | `StructuredFanoutTest` (fan-out + fail-fast) |

> ⚠️ JMH fork JVM thật + warmup → mỗi lần chạy đầy đủ tốn vài phút. Số trong
> docs là từ 1 lần chạy mẫu trên máy dev; chạy lại để có số của bạn — xu hướng
> mới là thứ cần nhớ, không phải con số tuyệt đối.

## Map tới docs

- [lessons/19-java-locking.md](../docs/lessons/19-java-locking.md)
- [lessons/19b-virtual-threads-deep.md](../docs/lessons/19b-virtual-threads-deep.md)
- [issues/19-redlock-correctness.md](../docs/issues/19-redlock-correctness.md)
