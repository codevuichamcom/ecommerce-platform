package com.ecom.lab.structured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class StructuredFanoutTest {

    private final StructuredFanout fanout = new StructuredFanout();

    @Test
    void allSubtasksSucceed_returnsAggregatedResult() throws Exception {
        var result = fanout.fanOut(
            () -> "cart-3-items",
            () -> "product-42",
            () -> 7,
            Duration.ofSeconds(2));

        assertEquals("cart-3-items", result.cart());
        assertEquals("product-42", result.product());
        assertEquals(7, result.inventory());
    }

    @Test
    void oneSubtaskFails_failsFastAndCancelsSiblings() {
        AtomicBoolean siblingFinished = new AtomicBoolean(false);

        ExecutionException ex = assertThrows(ExecutionException.class, () ->
            fanout.fanOut(
                () -> { throw new IllegalStateException("inventory down"); },
                () -> "product-ok",
                () -> {
                    // Sibling chậm: nếu structured concurrency hủy đúng thì
                    // body này bị interrupt TRƯỚC khi set flag.
                    Thread.sleep(1000);
                    siblingFinished.set(true);
                    return 99;
                },
                Duration.ofSeconds(5)));

        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals("inventory down", ex.getCause().getMessage());
        assertTrue(!siblingFinished.get(), "sibling chậm phải bị hủy khi 1 subtask fail (fail-fast)");
    }
}
