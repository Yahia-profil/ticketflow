package com.ticketflow.eventservice.dto;

import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.entities.EventStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record EventResponse(
        Long id,
        String title,
        String description,
        String category,
        String venue,
        String city,
        Instant startsAt,
        Instant endsAt,
        EventStatus status,
        int totalCapacity,
        int reservedCapacity,
        int availableCapacity,
        BigDecimal ticketPrice,
        Instant createdAt) {

    public static EventResponse from(Event e) {
        return new EventResponse(
                e.getId(),
                e.getTitle(),
                e.getDescription(),
                e.getCategory(),
                e.getVenue(),
                e.getCity(),
                e.getStartsAt(),
                e.getEndsAt(),
                e.getStatus(),
                e.getTotalCapacity(),
                e.getReservedCapacity(),
                e.availableCapacity(),
                e.getTicketPrice(),
                e.getCreatedAt());
    }
}
