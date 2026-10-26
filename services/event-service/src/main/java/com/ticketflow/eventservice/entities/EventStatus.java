package com.ticketflow.eventservice.entities;

/**
 * Publication lifecycle of an event (FR-004).
 * A draft is never exposed by the public catalogue.
 */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CLOSED,
    ARCHIVED
}
