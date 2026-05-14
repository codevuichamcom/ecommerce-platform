package com.ecommerce.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * Value Object — shipping address. Immutable record + JPA {@code @Embeddable}
 * flatten thành 5 column trong bảng orders.
 *
 * <p>{@code countryCode} = ISO 3166-1 alpha-2 (VN, US, JP). Validate ở
 * constructor — không để DB CHECK chịu trận một mình.
 */
@Embeddable
public record Address(
        @Column(name = "shipping_recipient") String recipient,
        @Column(name = "shipping_phone")     String phone,
        @Column(name = "shipping_line")      String line,
        @Column(name = "shipping_city")      String city,
        @Column(name = "shipping_country")   String countryCode
) {

    public Address {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(countryCode, "countryCode");
        if (countryCode.length() != 2) {
            throw new IllegalArgumentException("countryCode must be ISO 3166 alpha-2");
        }
    }
}
