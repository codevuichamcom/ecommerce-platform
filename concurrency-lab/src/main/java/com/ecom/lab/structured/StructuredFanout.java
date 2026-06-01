package com.ecom.lab.structured;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeoutException;

/**
 * Structured Concurrency fan-out (JEP 453 — PREVIEW ở Java 21).
 *
 * <p>Bài toán: trang chi tiết sản phẩm cần gộp 3 nguồn độc lập — cart, product,
 * inventory. Cách cũ {@code CompletableFuture.allOf} có 3 vấn đề:
 * <ul>
 *   <li>nếu 1 future fail, 2 future kia vẫn chạy tới hết → lãng phí + leak;</li>
 *   <li>hủy (cancel) phải tự gọi thủ công, dễ quên;</li>
 *   <li>quan hệ cha-con mờ → stacktrace/observability rối.</li>
 * </ul>
 *
 * <p>{@link StructuredTaskScope.ShutdownOnFailure} sửa cả ba: subtask là CON của
 * scope, 1 subtask fail → scope <b>tự hủy</b> các sibling (fail-fast), và toàn bộ
 * sống-chết trong cùng 1 try-with-resources (structured = vào đâu ra đó).
 *
 * <p>3 subtask chạy trên 3 virtual thread → fan-out gần như miễn phí.
 */
public final class StructuredFanout {

    /** Kết quả gộp — chỉ tồn tại khi CẢ BA subtask thành công. */
    public record Result<C, P, I>(C cart, P product, I inventory) {
    }

    /**
     * Fan-out 3 call, chờ tối đa {@code timeout}.
     *
     * @throws ExecutionException   1 subtask ném exception (fail-fast: sibling bị hủy)
     * @throws TimeoutException     quá hạn trước khi cả 3 xong
     * @throws InterruptedException thread gọi bị interrupt
     */
    public <C, P, I> Result<C, P, I> fanOut(
            Callable<C> cart,
            Callable<P> product,
            Callable<I> inventory,
            Duration timeout) throws ExecutionException, InterruptedException, TimeoutException {

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var cartTask = scope.fork(cart);
            var productTask = scope.fork(product);
            var inventoryTask = scope.fork(inventory);

            scope.joinUntil(Instant.now().plus(timeout));  // chờ tất cả HOẶC tới deadline
            scope.throwIfFailed();                         // có subtask fail → ném ngay

            return new Result<>(cartTask.get(), productTask.get(), inventoryTask.get());
        }
    }
}
