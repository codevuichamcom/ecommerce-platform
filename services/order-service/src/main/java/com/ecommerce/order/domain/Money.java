package com.ecommerce.order.domain;

import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * Value Object — số tiền + currency.
 *
 * <p>Lưu {@code amount} dạng long cents (vd 99.99 USD = 9999) để TRÁNH
 * {@code double} rounding error. NUMERIC ở DB tương đương BigDecimal — vẫn
 * dùng được nhưng overhead JPA + so sánh equals phức tạp. Cents long là
 * idiom phổ biến cho ecommerce mid-size (Stripe API cũng dùng).
 *
 * <p>{@code @Embeddable} cho phép JPA flatten Money thành 2 column trong
 * bảng cha (orders.total_amount + orders.total_currency).
 */
@Embeddable
public record Money(long amount, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be ISO 4217 (3 chars), got: " + currency);
        }
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be ≥ 0, got: " + amount);
        }
    }

    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount + other.amount, currency);
    }

    public Money multiply(int factor) {
        if (factor < 0) {
            throw new IllegalArgumentException("factor must be ≥ 0");
        }
        return new Money(this.amount * factor, currency);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: %s vs %s".formatted(this.currency, other.currency));
        }
    }
}
