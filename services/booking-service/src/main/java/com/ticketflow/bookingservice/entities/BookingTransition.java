package com.ticketflow.bookingservice.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Audit trail of one status change (rule of management 3: every change leaves a trace
 * that can be read back through the API).
 */
@Entity
@Table(name = "booking_transitions", indexes = @Index(columnList = "bookingId"))
public class BookingTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long bookingId;

    /** Null for the first transition, when the booking comes into existence. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private BookingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private BookingStatus toStatus;

    @Column(nullable = false, updatable = false, length = 255)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant at;

    protected BookingTransition() {
        // for JPA
    }

    public BookingTransition(Long bookingId, BookingStatus fromStatus, BookingStatus toStatus,
                             String reason, Instant at) {
        this.bookingId = bookingId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public BookingStatus getFromStatus() {
        return fromStatus;
    }

    public BookingStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public Instant getAt() {
        return at;
    }
}
