package com.ticketflow.bookingservice.service;

import com.ticketflow.bookingservice.dto.BookingRequest;
import com.ticketflow.bookingservice.dto.PaymentRequest;
import com.ticketflow.bookingservice.dto.TransitionResponse;
import com.ticketflow.bookingservice.entities.Booking;
import com.ticketflow.bookingservice.entities.BookingStatus;
import com.ticketflow.bookingservice.entities.BookingTransition;
import com.ticketflow.bookingservice.entities.PaymentOutcome;
import com.ticketflow.bookingservice.exceptions.BookingNotFoundException;
import com.ticketflow.bookingservice.exceptions.IdempotencyConflictException;
import com.ticketflow.bookingservice.exceptions.InvalidBookingStateException;
import com.ticketflow.bookingservice.repos.BookingRepo;
import com.ticketflow.bookingservice.repos.BookingTransitionRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the reservation lifecycle. The valid transitions are declared here and every change
 * is written to {@code booking_transitions}, so a booking can always be explained
 * (rule of management 3).
 */
@Service
@Transactional
public class BookingService {

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED = Map.of(
            BookingStatus.PENDING_PAYMENT, EnumSet.of(
                    BookingStatus.CONFIRMED,
                    BookingStatus.PAYMENT_FAILED,
                    BookingStatus.EXPIRED,
                    BookingStatus.CANCELLED),
            BookingStatus.PAYMENT_FAILED, EnumSet.of(BookingStatus.CANCELLED),
            BookingStatus.CONFIRMED, EnumSet.of(BookingStatus.CANCELLED));

    private final BookingRepo bookings;
    private final BookingTransitionRepo transitions;
    private final Duration holdDuration;

    public BookingService(BookingRepo bookings,
                          BookingTransitionRepo transitions,
                          @Value("${ticketflow.booking.hold-duration:PT15M}") String holdDuration) {
        this.holdDuration = Duration.parse(holdDuration);
        if (this.holdDuration.isZero() || this.holdDuration.isNegative()) {
            throw new IllegalArgumentException("ticketflow.booking.hold-duration must be positive");
        }
        this.bookings = bookings;
        this.transitions = transitions;
    }

    /** A replayed request returns the original booking instead of creating a second one. */
    public record CreateResult(Booking booking, boolean replayed) {
    }

    public CreateResult create(BookingRequest request) {
        String key = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString()
                : request.idempotencyKey().trim();

        Booking existing = bookings.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            if (!samePayload(existing, request)) {
                throw new IdempotencyConflictException(
                        "Idempotency key " + key + " was already used with a different payload");
            }
            return new CreateResult(existing, true);
        }

        Instant now = Instant.now();
        Booking booking = bookings.save(new Booking(
                request.customerId(),
                request.eventId(),
                request.quantity(),
                request.unitPrice(),
                key,
                now,
                now.plus(holdDuration)));
        trace(booking, null, BookingStatus.PENDING_PAYMENT, "Booking created, seats held", now);
        return new CreateResult(booking, false);
    }

    @Transactional(readOnly = true)
    public Booking get(Long id) {
        return bookings.findById(id).orElseThrow(() -> new BookingNotFoundException(id));
    }

    /** Without a customer id the whole ledger is returned, which is handy for demos. */
    @Transactional(readOnly = true)
    public List<Booking> list(Long customerId) {
        return customerId == null
                ? bookings.findAllByOrderByCreatedAtDesc()
                : bookings.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    @Transactional(readOnly = true)
    public List<TransitionResponse> history(Long id) {
        get(id);
        return transitions.findByBookingIdOrderByAtAsc(id).stream()
                .map(TransitionResponse::from)
                .toList();
    }

    /**
     * Consumes the result of the simulated payment (RF-05). {@code UNAVAILABLE} leaves the
     * booking pending on purpose: the call can be retried without creating a second hold.
     */
    public Booking pay(Long id, PaymentRequest request) {
        PaymentOutcome outcome = parseOutcome(request);
        Booking booking = get(id);
        if (!booking.isPending()) {
            throw new InvalidBookingStateException(
                    "Only a booking awaiting payment can be paid, " + id + " is " + booking.getStatus());
        }

        Instant now = Instant.now();
        switch (outcome) {
            case APPROVED -> {
                transition(booking, BookingStatus.CONFIRMED, "Payment approved", now);
                booking.confirm(now);
            }
            case DECLINED -> {
                transition(booking, BookingStatus.PAYMENT_FAILED, "Payment declined by the provider", now);
                booking.fail("Payment declined by the provider", now);
            }
            case UNAVAILABLE -> {
                return booking;
            }
        }
        return bookings.save(booking);
    }

    public Booking cancel(Long id, String reason) {
        Booking booking = get(id);
        Instant now = Instant.now();
        transition(booking, BookingStatus.CANCELLED, reason, now);
        booking.cancel(reason, now);
        return bookings.save(booking);
    }

    /** A hold that outlived its deadline is expired and its seats are given back. */
    public Booking expire(Long id) {
        Booking booking = get(id);
        requirePending(booking);
        if (booking.getExpiresAt().isAfter(Instant.now())) {
            throw new InvalidBookingStateException(
                    "Booking " + id + " is still held until " + booking.getExpiresAt());
        }
        Instant now = Instant.now();
        transition(booking, BookingStatus.EXPIRED, "Payment hold elapsed", now);
        booking.expire(now);
        return bookings.save(booking);
    }

    /** Sweeps every overdue hold; called on a fixed delay so demo data stays consistent. */
    @Scheduled(fixedDelayString = "${ticketflow.booking.sweep-interval:60000}")
    @Transactional
    public int expireOverdue() {
        Instant now = Instant.now();
        List<Booking> overdue = bookings.findByStatusAndExpiresAtBefore(BookingStatus.PENDING_PAYMENT, now);
        for (Booking booking : overdue) {
            transition(booking, BookingStatus.EXPIRED, "Payment hold elapsed", now);
            booking.expire(now);
            bookings.save(booking);
        }
        return overdue.size();
    }


    private void requirePending(Booking booking) {
        if (!booking.isPending()) {
            throw new InvalidBookingStateException(
                    "Booking " + booking.getId() + " is " + booking.getStatus() + ", not awaiting payment");
        }
    }

    private void transition(Booking booking, BookingStatus target, String reason, Instant at) {
        BookingStatus from = booking.getStatus();
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(target)) {
            throw new InvalidBookingStateException(
                    "Booking " + booking.getId() + " cannot move from " + from + " to " + target);
        }
        trace(booking, from, target, reason, at);
    }

    private void trace(Booking booking, BookingStatus from, BookingStatus to, String reason, Instant at) {
        transitions.save(new BookingTransition(booking.getId(), from, to, reason, at));
    }

    private static PaymentOutcome parseOutcome(PaymentRequest request) {
        try {
            return PaymentOutcome.valueOf(request.outcome().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown payment outcome '" + request.outcome() + "': expected APPROVED, DECLINED or UNAVAILABLE");
        }
    }

    private static boolean samePayload(Booking existing, BookingRequest request) {
        return existing.getEventId().equals(request.eventId())
                && existing.getCustomerId().equals(request.customerId())
                && existing.getQuantity() == request.quantity()
                && existing.getUnitPrice().compareTo(request.unitPrice()) == 0;
    }
}
