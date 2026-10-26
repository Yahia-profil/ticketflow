package com.ticketflow.bookingservice.entities;

/** Lifecycle of a reservation (rule of management 3). */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED
}
