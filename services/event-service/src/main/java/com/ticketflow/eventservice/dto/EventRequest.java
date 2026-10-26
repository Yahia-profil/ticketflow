package com.ticketflow.eventservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public record EventRequest(
        @NotBlank String title,
        String description,
        @NotBlank String category,
        @NotBlank String venue,
        @NotBlank String city,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @Min(value = 1, message = "totalCapacity must be at least 1")
        int totalCapacity,
        @NotNull @DecimalMin(value = "0", message = "ticketPrice must be positive")
        BigDecimal ticketPrice) {
}
