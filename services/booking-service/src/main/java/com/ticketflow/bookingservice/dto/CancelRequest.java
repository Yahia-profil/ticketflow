package com.ticketflow.bookingservice.dto;

/** Optional body of the cancel endpoint; the reason ends up in the audit trail. */
public record CancelRequest(String reason) {
}
