package com.ticketflow.bookingservice.exceptions;

/** The event exists in the catalogue but is not open for reservations (not PUBLISHED). */
public class EventNotBookableException extends RuntimeException {

    public EventNotBookableException(Long eventId, String status) {
        super("Event " + eventId + " is " + status + " and cannot accept reservations");
    }
}
