package com.ticketflow.eventservice.exceptions;

public class CapacityUnavailableException extends RuntimeException {

    public CapacityUnavailableException(Long id, int quantity) {
        super("Not enough seats left on event " + id + " to reserve " + quantity);
    }
}