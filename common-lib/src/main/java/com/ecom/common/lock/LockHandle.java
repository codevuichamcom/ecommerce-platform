package com.ecom.common.lock;

/**
 * Token chứng minh quyền sở hữu 1 distributed lock đã acquire.
 *
 * @param key          tên resource bị khoá (vd {@code "snapshot:2026-06-01"})
 * @param ownerToken   UUID ngẫu nhiên — chỉ owner đúng token này mới release được
 *                     (chống xoá nhầm lock của process khác sau khi TTL expire)
 * @param fencingToken số nguyên TĂNG ĐƠN ĐIỆU mỗi lần acquire. Đây mới là thứ
 *                     bảo đảm <b>correctness</b>: resource (DB) từ chối write
 *                     mang token nhỏ hơn token đã thấy → chặn split-brain do
 *                     GC pause / network delay. Lock alone KHÔNG đủ.
 */
public record LockHandle(String key, String ownerToken, long fencingToken) {
}
