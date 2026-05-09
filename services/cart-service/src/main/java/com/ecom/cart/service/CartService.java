package com.ecom.cart.service;

import com.ecom.cart.config.CartProperties;
import com.ecom.cart.domain.CartId;
import com.ecom.cart.web.dto.CartItemResponse;
import com.ecom.cart.web.dto.CartResponse;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Cart core. Thiết kế xoay quanh 3 quyết định kỹ thuật:
 *
 * <ol>
 *   <li><b>HINCRBY thay vì HGET → HSET</b>. Add 2 SKU đồng thời từ 2 tab phải
 *       cộng dồn, không lost-update. {@code opsForHash().increment(...)}
 *       map sang HINCRBY, atomic ở field-level.</li>
 *   <li><b>EXPIRE refresh chỉ khi mutation</b>. Read không refresh — nếu
 *       mỗi GET đều bump TTL, cart không bao giờ expire khi user chỉ idle
 *       browse → vi phạm "7 ngày không hoạt động → drop".</li>
 *   <li><b>Negative qty sau update không tồn tại</b>. UpdateItemRequest
 *       validate {@code @Min(0)}; qty=0 → HDEL. {@code remove} là alias rõ
 *       ràng cho UX.</li>
 * </ol>
 *
 * <p>Merge anonymous → user: HGETALL → loop HINCRBY user → DEL anon. Đặt
 * trong {@link Transactional} (Redis transaction = MULTI/EXEC) chỉ là
 * defense-in-depth — nếu Redis crash giữa chừng, replay merge vẫn idempotent
 * vì anon key chưa DEL (worst case: double-merge bị HINCRBY 2 lần). Ép
 * caller dùng request-id dedup nếu cần idempotency strict (Day 10 sẽ giải
 * quyết pattern này tử tế hơn).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final StringRedisTemplate redis;
    private final CartProperties props;

    public CartResponse addItem(CartId id, String sku, int qty) {
        validateQtyDelta(qty);
        String key = id.redisKey();

        HashOperations<String, Object, Object> ops = redis.opsForHash();
        long currentSize = ops.size(key);
        boolean isNewField = !ops.hasKey(key, sku);
        if (isNewField && currentSize >= props.maxItemsPerCart()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Vượt giới hạn " + props.maxItemsPerCart() + " SKU/cart");
        }

        Long newQty = ops.increment(key, sku, qty);
        if (newQty == null) {
            // KHÔNG nên xảy ra — Lettuce reactive khác signature, ở đây sync.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "HINCRBY trả null");
        }
        if (newQty > props.maxQtyPerItem()) {
            // Rollback bằng decrement — không block-and-check trước add vì sẽ
            // có race (TOCTOU). Sau khi tăng quá cap thì hạ về cap, accept
            // 1 round-trip extra.
            long overflow = newQty - props.maxQtyPerItem();
            ops.increment(key, sku, -overflow);
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Qty/SKU tối đa " + props.maxQtyPerItem());
        }
        refreshTtl(key);
        return readCart(id);
    }

    public CartResponse updateItem(CartId id, String sku, int qty) {
        if (qty < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "qty < 0");
        }
        if (qty > props.maxQtyPerItem()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Qty/SKU tối đa " + props.maxQtyPerItem());
        }
        String key = id.redisKey();
        HashOperations<String, Object, Object> ops = redis.opsForHash();

        if (!ops.hasKey(key, sku)) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND, "SKU " + sku + " không có trong cart");
        }
        if (qty == 0) {
            ops.delete(key, sku);
        } else {
            ops.put(key, sku, Integer.toString(qty));
        }
        refreshTtl(key);
        return readCart(id);
    }

    public CartResponse removeItem(CartId id, String sku) {
        String key = id.redisKey();
        Long deleted = redis.opsForHash().delete(key, sku);
        if (deleted == null || deleted == 0) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND, "SKU " + sku + " không có trong cart");
        }
        refreshTtl(key);
        return readCart(id);
    }

    public void clear(CartId id) {
        redis.delete(id.redisKey());
    }

    public CartResponse getCart(CartId id) {
        return readCart(id);
    }

    /**
     * Merge anonymous cart vào user cart. Conflict rule: <b>sum quantity per
     * SKU</b>, sau đó cap by {@code maxQtyPerItem} (UX: hiện toast "đã giảm
     * còn N do giới hạn").
     *
     * <p>Caller gọi sau login flow. Idempotency: caller nên dedup theo
     * (anonToken, userId) ở client; service thì idempotent theo nghĩa
     * "merge xong là DEL anon → call lần 2 không có gì để merge".
     */
    @Transactional
    public CartResponse merge(String anonToken, UUID userId) {
        CartId.Anonymous anon = new CartId.Anonymous(anonToken);
        CartId.User user = new CartId.User(userId);

        Map<Object, Object> anonItems = redis.opsForHash().entries(anon.redisKey());
        if (anonItems.isEmpty()) {
            return readCart(user);
        }

        HashOperations<String, Object, Object> ops = redis.opsForHash();
        for (Map.Entry<Object, Object> e : anonItems.entrySet()) {
            String sku = e.getKey().toString();
            int qty = parseQtyOrZero(e.getValue());
            if (qty <= 0) continue;

            Long merged = ops.increment(user.redisKey(), sku, qty);
            if (merged != null && merged > props.maxQtyPerItem()) {
                long overflow = merged - props.maxQtyPerItem();
                ops.increment(user.redisKey(), sku, -overflow);
            }
        }
        // Cap distinct SKU sau merge (anon có 80 SKU + user có 50 = vượt 100).
        // Approach đơn giản: drop SKU dư từ anon; production tốt hơn thì
        // prompt user chọn — Day 5 chấp nhận trade-off cho POC.
        long sizeAfter = ops.size(user.redisKey());
        if (sizeAfter > props.maxItemsPerCart()) {
            log.warn("Cart user={} vượt cap {} SKU sau merge ({}). Sẽ trim.",
                    userId, props.maxItemsPerCart(), sizeAfter);
            // TODO Day 7: trim deterministic theo addedAt, hiện tại để Redis
            // tự nhiên — junior đọc đoạn này phải hiểu đây là "known issue".
        }

        refreshTtl(user.redisKey());
        redis.delete(anon.redisKey());
        return readCart(user);
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private void validateQtyDelta(int qty) {
        if (qty < 1) throw new BusinessException(ErrorCode.BAD_REQUEST, "qty < 1");
        if (qty > props.maxQtyPerItem()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Qty/SKU tối đa " + props.maxQtyPerItem());
        }
    }

    private void refreshTtl(String key) {
        redis.expire(key, props.ttl().toSeconds(), TimeUnit.SECONDS);
    }

    private CartResponse readCart(CartId id) {
        String key = id.redisKey();
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        List<CartItemResponse> items = raw.entrySet().stream()
                .map(e -> new CartItemResponse(e.getKey().toString(), parseQtyOrZero(e.getValue())))
                .filter(item -> item.qty() > 0)
                .sorted(Comparator.comparing(CartItemResponse::sku))
                .toList();
        int total = items.stream().mapToInt(CartItemResponse::qty).sum();

        Long ttlSec = redis.getExpire(key, TimeUnit.SECONDS);
        Instant expiresAt = (ttlSec != null && ttlSec > 0) ? Instant.now().plusSeconds(ttlSec) : null;
        return new CartResponse(key, items, total, expiresAt);
    }

    private static int parseQtyOrZero(Object v) {
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
