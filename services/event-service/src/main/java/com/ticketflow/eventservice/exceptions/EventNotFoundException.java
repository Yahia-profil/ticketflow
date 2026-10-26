package com.ticketflow.eventservice.exceptions;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(Long id) {
        super("Event " + id + " was not found");
    }
}