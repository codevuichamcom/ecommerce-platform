package com.ecom.common.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * Distributed lock cross-process (vd: leader-elect cho scheduled job chạy nhiều
 * instance). Hợp đồng tối thiểu — best-effort mutual exclusion.
 *
 * <p><b>Cảnh báo quan trọng</b>: lock này KHÔNG đủ để bảo vệ critical write
 * khỏi GC pause / STW dài. Process A có thể acquire lock, vào GC 30s, lock
 * expire, B acquire, A tỉnh dậy vẫn nghĩ mình giữ lock → cả hai cùng write
 * (split-brain). Để CORRECT, resource đích phải kiểm
 * {@link LockHandle#fencingToken()} và từ chối token cũ.
 *
 * <p>Xem [issue 19](../../../../../../docs/issues/19-redlock-correctness.md).
 */
public interface DistributedLock {

    /**
     * Thử acquire không chờ (non-blocking).
     *
     * @param key resource cần khoá
     * @param ttl thời gian giữ lock trước khi tự expire (chống deadlock khi
     *            process chết). Chọn &gt; thời gian xử lý dự kiến + buffer.
     * @return {@link LockHandle} kèm fencing token nếu chiếm được, rỗng nếu đã
     *         có process khác giữ.
     */
    Optional<LockHandle> tryAcquire(String key, Duration ttl);

    /**
     * Release — CHỈ xoá nếu mình vẫn là owner đúng (so token), atomic qua Lua.
     *
     * @return {@code true} nếu thực sự xoá; {@code false} nếu lock đã bị expire
     *         hoặc bị process khác chiếm (cảnh báo: việc bạn vừa làm có thể đã
     *         chạy đè lên người khác — đây là lúc fencing token cứu bạn).
     */
    boolean release(LockHandle handle);
}
