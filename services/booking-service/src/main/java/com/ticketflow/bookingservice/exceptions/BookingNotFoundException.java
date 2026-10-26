package com.ticketflow.bookingservice.exceptions;

public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(Long id) {
        super("Booking " + id + " was not found");
    }
}
