package com.ticketflow.eventservice.dto;

import jakarta.validation.constraints.Min;

/**
 * Body of the internal reserve/release calls.
 * {@code idempotencyKey} lets a caller retry safely: it is stored with the booking that
 * triggered the call, so a duplicated request does not take seats twice.
 */
public record SeatRequest(
        @Min(value = 1, message = "quantity must be at least 1")
        int quantity,
        String idempotencyKey) {
}
