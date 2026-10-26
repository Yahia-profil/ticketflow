package com.ticketflow.bookingservice.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One reservation. The seat count is only held here for the duration of the payment:
 * the authoritative capacity lives in event-service.
 */
@Entity
@Table(name = "bookings", uniqueConstraints = @UniqueConstraint(
        name = "uk_bookings_idempotency", columnNames = "idempotencyKey"))
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private Long customerId;

    @Column(nullable = false, updatable = false)
    private Long eventId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BookingStatus status;

    @Column(length = 255)
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** Deadline of the temporary hold: after this instant the seats go back (rule of management 2). */
    @Column(nullable = false)
    private Instant expiresAt;

    private Instant updatedAt;

    protected Booking() {
        // for JPA
    }

    public Booking(Long customerId, Long eventId, int quantity, BigDecimal unitPrice,
                   String idempotencyKey, Instant createdAt, Instant expiresAt) {
        this.customerId = customerId;
        this.eventId = eventId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        this.idempotencyKey = idempotencyKey;
        this.status = BookingStatus.PENDING_PAYMENT;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void confirm(Instant at) {
        this.status = BookingStatus.CONFIRMED;
        this.updatedAt = at;
    }

    public void fail(String reason, Instant at) {
        this.status = BookingStatus.PAYMENT_FAILED;
        this.failureReason = reason;
        this.updatedAt = at;
    }

    public void expire(Instant at) {
        this.status = BookingStatus.EXPIRED;
        this.failureReason = "The payment hold elapsed";
        this.updatedAt = at;
    }

    public void cancel(String reason, Instant at) {
        this.status = BookingStatus.CANCELLED;
        this.failureReason = reason;
        this.updatedAt = at;
    }

    public boolean isPending() {
        return status == BookingStatus.PENDING_PAYMENT;
    }

    public boolean isHoldingSeats() {
        return status == BookingStatus.PENDING_PAYMENT || status == BookingStatus.CONFIRMED;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getEventId() {
        return eventId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
