package com.ecom.inventory.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test cho aggregate Stock — KHÔNG load Spring context.
 *
 * <p>Mục đích: chứng minh invariant enforce TRONG aggregate, không phải
 * ở service. Caller bypass service vẫn không phá invariant.
 */
class StockTest {

    @Test
    void create_withZeroQty_isAllowed() {
        Stock stock = Stock.create("SKU-1", 0);

        assertThat(stock.getSku()).isEqualTo("SKU-1");
        assertThat(stock.getQuantity()).isZero();
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.available()).isZero();
    }

    @Test
    void create_withNegativeQty_throws() {
        assertThatThrownBy(() -> Stock.create("SKU-1", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("≥ 0");
    }

    @Test
    void create_withBlankSku_throws() {
        assertThatThrownBy(() -> Stock.create("  ", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserve_withinAvailable_decreasesAvailableIncreasesReserved() {
        Stock stock = Stock.create("SKU-1", 100);

        stock.reserve(30);

        assertThat(stock.getReserved()).isEqualTo(30);
        assertThat(stock.available()).isEqualTo(70);
    }

    @Test
    void reserve_exceedsAvailable_throwsBusinessException() {
        Stock stock = Stock.create("SKU-1", 5);

        assertThatThrownBy(() -> stock.reserve(10))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("requested=10")
                .hasMessageContaining("available=5");
    }

    @Test
    void reserve_zeroOrNegative_throws() {
        Stock stock = Stock.create("SKU-1", 100);

        assertThatThrownBy(() -> stock.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.reserve(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void release_moreThanReserved_throwsIllegalState() {
        // Programming error, không phải user input — IllegalState là phù hợp.
        Stock stock = Stock.create("SKU-1", 100);
        stock.reserve(20);

        assertThatThrownBy(() -> stock.release(50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only 20 reserved");
    }

    @Test
    void release_validQty_decreasesReserved() {
        Stock stock = Stock.create("SKU-1", 100);
        stock.reserve(20);
        stock.release(15);

        assertThat(stock.getReserved()).isEqualTo(5);
        assertThat(stock.available()).isEqualTo(95);
    }

    @Test
    void confirm_decreasesQuantityAndReserved() {
        Stock stock = Stock.create("SKU-1", 100);
        stock.reserve(30);
        stock.confirm(30);

        assertThat(stock.getQuantity()).isEqualTo(70);
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.available()).isEqualTo(70);
    }
}
