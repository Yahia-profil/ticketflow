package com.ticketflow.bookingservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * A reservation request. {@code unitPrice} is echoed by the caller until the OpenFeign step
 * reads it from event-service; {@code idempotencyKey} makes replays safe (rule of management 5).
 */
public record BookingRequest(
        @NotNull Long eventId,
        @NotNull Long customerId,
        @Min(value = 1, message = "quantity must be at least 1")
        int quantity,
        @NotNull @DecimalMin(value = "0", message = "unitPrice must be positive")
        BigDecimal unitPrice,
        String idempotencyKey) {
}
