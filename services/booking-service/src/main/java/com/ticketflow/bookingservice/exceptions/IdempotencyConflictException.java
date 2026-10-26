package com.ticketflow.bookingservice.exceptions;

/** Same idempotency key sent with a different payload (rule of management 5). */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
