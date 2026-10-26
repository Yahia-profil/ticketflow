package com.ticketflow.eventservice.dto;

import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.entities.EventStatus;

/** Seat inventory of an event; the number sent to booking-service over OpenFeign. */
public record AvailabilityResponse(
        Long eventId,
        EventStatus status,
        int totalCapacity,
        int reservedCapacity,
        int availableCapacity) {

    public static AvailabilityResponse from(Event e) {
        return new AvailabilityResponse(
                e.getId(),
                e.getStatus(),
                e.getTotalCapacity(),
                e.getReservedCapacity(),
                e.availableCapacity());
    }
}
