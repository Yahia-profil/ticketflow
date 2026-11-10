package com.ticketflow.bookingservice.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only mirror of the JSON returned by {@code GET /api/events/{id}} on event-service.
 * Only properties that really exist in that contract are declared; unknown ones are
 * ignored so a field added on the producer side cannot break the consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventResponse(
        Long id,
        String title,
        String description,
        String category,
        String venue,
        String city,
        Instant startsAt,
        Instant endsAt,
        String status,
        int totalCapacity,
        int reservedCapacity,
        int availableCapacity,
        BigDecimal ticketPrice,
        Instant createdAt) {

    /** Only a published event can be booked; closed, draft or archived ones cannot. */
    public boolean isPublished() {
        return "PUBLISHED".equalsIgnoreCase(status);
    }
}
