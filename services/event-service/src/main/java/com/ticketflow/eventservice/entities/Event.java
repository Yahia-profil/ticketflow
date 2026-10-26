package com.ticketflow.eventservice.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Catalogue entry owned by event-service.
 *
 * <p>event-service is the single writer of the capacity: the reserved seats live here and
 * are updated with atomic statements, which is what booking-service will call through
 * OpenFeign in the next step.</p>
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 4000)
    private String description;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String venue;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status = EventStatus.DRAFT;

    @Column(nullable = false)
    private int totalCapacity;

    @Column(nullable = false)
    private int reservedCapacity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal ticketPrice;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected Event() {
        // required by JPA
    }

    public Event(String title, String description, String category, String venue, String city,
                 Instant startsAt, Instant endsAt, int totalCapacity, BigDecimal ticketPrice) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.venue = venue;
        this.city = city;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.totalCapacity = totalCapacity;
        this.ticketPrice = ticketPrice;
    }

    public void update(String title, String description, String category, String venue, String city,
                       Instant startsAt, Instant endsAt, int totalCapacity, BigDecimal ticketPrice) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.venue = venue;
        this.city = city;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.totalCapacity = totalCapacity;
        this.ticketPrice = ticketPrice;
        this.updatedAt = Instant.now();
    }

    /** Transitions are guarded by the service, which owns the business rules. */
    public void publish() {
        this.status = EventStatus.PUBLISHED;
        this.updatedAt = Instant.now();
    }

    public void close() {
        this.status = EventStatus.CLOSED;
        this.updatedAt = Instant.now();
    }

    /** Removes the event from the public catalogue for good (FR-004). */
    public void archive() {
        this.status = EventStatus.ARCHIVED;
        this.updatedAt = Instant.now();
    }

    public boolean isBookable() {
        return status == EventStatus.PUBLISHED;
    }

    public int availableCapacity() {
        return totalCapacity - reservedCapacity;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getVenue() { return venue; }
    public String getCity() { return city; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public EventStatus getStatus() { return status; }
    public int getTotalCapacity() { return totalCapacity; }
    public int getReservedCapacity() { return reservedCapacity; }
    public BigDecimal getTicketPrice() { return ticketPrice; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
