package com.ticketflow.bookingservice.dto;

import jakarta.validation.constraints.NotBlank;

/** Input of the simulated payment call (RF-05). */
public record PaymentRequest(@NotBlank String outcome) {
}
