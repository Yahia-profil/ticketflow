package com.ticketflow.bookingservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A reservation request. The price is not part of the body: booking-service reads
 * {@code ticketPrice} from event-service through {@code EventClient}.
 * {@code idempotencyKey} makes replays safe (rule of management 5).
 */
public record BookingRequest(
        @NotNull Long eventId,
        @NotNull Long customerId,
        @Min(value = 1, message = "quantity must be at least 1")
        int quantity,
        String idempotencyKey) {
}
