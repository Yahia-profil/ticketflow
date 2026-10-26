package com.ticketflow.bookingservice.dto;

import com.ticketflow.bookingservice.entities.Booking;
import com.ticketflow.bookingservice.entities.BookingStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record BookingResponse(
        Long id,
        Long eventId,
        Long customerId,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        BookingStatus status,
        String failureReason,
        Instant createdAt,
        Instant expiresAt) {

    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getEventId(),
                booking.getCustomerId(),
                booking.getQuantity(),
                booking.getUnitPrice(),
                booking.getTotalAmount(),
                booking.getStatus(),
                booking.getFailureReason(),
                booking.getCreatedAt(),
                booking.getExpiresAt());
    }
}
