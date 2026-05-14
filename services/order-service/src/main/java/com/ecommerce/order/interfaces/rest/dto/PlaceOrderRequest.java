package com.ecommerce.order.interfaces.rest.dto;

import com.ecommerce.order.domain.Address;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PlaceOrderRequest(
        @Valid @NotBlank String currency,
        @Valid ShippingAddressDto shipping,
        @Size(max = 80) String idempotencyKey) {

    public record ShippingAddressDto(
            @NotBlank @Size(max = 120) String recipient,
            @NotBlank @Size(max = 20)  String phone,
            @NotBlank @Size(max = 255) String line,
            @NotBlank @Size(max = 80)  String city,
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode) {

        public Address toAddress() {
            return new Address(recipient, phone, line, city, countryCode);
        }
    }
}
