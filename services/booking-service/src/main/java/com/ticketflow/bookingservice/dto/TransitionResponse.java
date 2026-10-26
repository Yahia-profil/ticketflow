package com.ticketflow.bookingservice.dto;

import com.ticketflow.bookingservice.entities.BookingStatus;
import com.ticketflow.bookingservice.entities.BookingTransition;
import java.time.Instant;

public record TransitionResponse(
        Long bookingId,
        BookingStatus from,
        BookingStatus to,
        String reason,
        Instant at) {

    public static TransitionResponse from(BookingTransition transition) {
        return new TransitionResponse(
                transition.getBookingId(),
                transition.getFromStatus(),
                transition.getToStatus(),
                transition.getReason(),
                transition.getAt());
    }
}
