package com.ticketflow.bookingservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.bookingservice.clients.EventClient;
import com.ticketflow.bookingservice.clients.EventResponse;
import com.ticketflow.bookingservice.dto.BookingRequest;
import com.ticketflow.bookingservice.dto.PaymentRequest;
import com.ticketflow.bookingservice.entities.Booking;
import com.ticketflow.bookingservice.entities.BookingStatus;
import com.ticketflow.bookingservice.entities.BookingTransition;
import com.ticketflow.bookingservice.exceptions.BookingNotFoundException;
import com.ticketflow.bookingservice.exceptions.EventNotBookableException;
import com.ticketflow.bookingservice.exceptions.IdempotencyConflictException;
import com.ticketflow.bookingservice.exceptions.InvalidBookingStateException;
import com.ticketflow.bookingservice.repos.BookingRepo;
import com.ticketflow.bookingservice.repos.BookingTransitionRepo;
import feign.FeignException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    BookingRepo bookings;

    @Mock
    BookingTransitionRepo transitions;

    @Mock
    EventClient eventClient;

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookings, transitions, eventClient, "PT15M");
    }

    private BookingRequest request(String key) {
        return new BookingRequest(7L, 42L, 3, key);
    }

    private EventResponse event(String status, String price) {
        Instant startsAt = Instant.now().plus(Duration.ofDays(10));
        return new EventResponse(7L, "Jazz night", "Live jazz", "CONCERT", "Opera", "Alger",
                startsAt, startsAt.plus(Duration.ofHours(3)), status,
                10, 0, 10, new BigDecimal(price), Instant.now());
    }

    private Booking savedBooking(String key) {
        return new Booking(42L, 7L, 3, new BigDecimal("250.00"), key,
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));
    }

    @Test
    void createStartsAPendingHoldWithAFifteenMinuteDeadline() {
        when(bookings.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(eventClient.getById(7L)).thenReturn(event("PUBLISHED", "250.00"));
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingService.CreateResult result = service.create(request("k1"));

        assertThat(result.replayed()).isFalse();
        Booking booking = result.booking();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.getUnitPrice()).isEqualByComparingTo("250.00");
        assertThat(booking.getTotalAmount()).isEqualByComparingTo("750.00");
        assertThat(Duration.between(booking.getCreatedAt(), booking.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(15));

        ArgumentCaptor<BookingTransition> captor = ArgumentCaptor.forClass(BookingTransition.class);
        verify(transitions).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isNull();
        assertThat(captor.getValue().getToStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void thePriceComesFromEventServiceNotFromTheCaller() {
        when(bookings.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(eventClient.getById(7L)).thenReturn(event("PUBLISHED", "300.00"));
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingService.CreateResult result = service.create(request("k1"));

        assertThat(result.booking().getUnitPrice()).isEqualByComparingTo("300.00");
        assertThat(result.booking().getTotalAmount()).isEqualByComparingTo("900.00");
    }

    @Test
    void anEventThatIsNotPublishedIsRefusedBeforeAnyWrite() {
        when(bookings.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(eventClient.getById(7L)).thenReturn(event("CLOSED", "250.00"));

        assertThatThrownBy(() -> service.create(request("k1")))
                .isInstanceOf(EventNotBookableException.class)
                .hasMessageContaining("CLOSED");

        verify(bookings, never()).save(any());
        verify(transitions, never()).save(any());
    }

    @Test
    void aRemoteNotFoundIsPropagatedAndNothingIsWritten() {
        when(bookings.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(eventClient.getById(7L)).thenThrow(mock(FeignException.NotFound.class));

        assertThatThrownBy(() -> service.create(request("k1")))
                .isInstanceOf(FeignException.NotFound.class);

        verify(bookings, never()).save(any());
        verify(transitions, never()).save(any());
    }

    @Test
    void aMissingKeyIsGeneratedForTheCaller() {
        when(bookings.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(eventClient.getById(7L)).thenReturn(event("PUBLISHED", "250.00"));
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingService.CreateResult result = service.create(request(null));

        assertThat(result.booking().getIdempotencyKey()).isNotBlank();
    }

    @Test
    void replayingTheSamePayloadReturnsTheOriginalBooking() {
        Booking original = savedBooking("k1");
        when(bookings.findByIdempotencyKey("k1")).thenReturn(Optional.of(original));

        BookingService.CreateResult result = service.create(request("k1"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.booking()).isSameAs(original);
        verify(eventClient, never()).getById(any());
        verify(bookings, never()).save(any());
        verify(transitions, never()).save(any());
    }

    @Test
    void replayingADifferentPayloadIsAConflict() {
        when(bookings.findByIdempotencyKey("k1")).thenReturn(Optional.of(savedBooking("k1")));

        assertThatThrownBy(() -> service.create(new BookingRequest(999L, 42L, 3, "k1")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void anApprovedPaymentConfirmsTheBooking() {
        Booking booking = savedBooking("k1");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        Booking paid = service.pay(1L, new PaymentRequest("approved"));

        assertThat(paid.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paid.getUpdatedAt()).isNotNull();
        verify(transitions).save(any());
    }

    @Test
    void aDeclinedPaymentFailsTheBooking() {
        Booking booking = savedBooking("k1");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        Booking failed = service.pay(1L, new PaymentRequest("DECLINED"));

        assertThat(failed.getStatus()).isEqualTo(BookingStatus.PAYMENT_FAILED);
        assertThat(failed.getFailureReason()).contains("declined");
    }

    @Test
    void anUnavailableProviderLeavesTheHoldRetryable() {
        Booking booking = savedBooking("k1");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));

        Booking stillPending = service.pay(1L, new PaymentRequest("UNAVAILABLE"));

        assertThat(stillPending.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        verify(transitions, never()).save(any());
    }

    @Test
    void anUnknownOutcomeIsRejected() {
        assertThatThrownBy(() -> service.pay(1L, new PaymentRequest("WHATEVER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    void aConfirmedBookingCannotBePaidTwice() {
        Booking booking = savedBooking("k1");
        booking.confirm(Instant.now());
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.pay(1L, new PaymentRequest("APPROVED")))
                .isInstanceOf(InvalidBookingStateException.class);
    }

    @Test
    void aPendingBookingCanBeCancelled() {
        Booking booking = savedBooking("k1");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        Booking cancelled = service.cancel(1L, "changed my mind");

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.getFailureReason()).isEqualTo("changed my mind");
    }

    @Test
    void anExpiredBookingIsTerminal() {
        Booking booking = savedBooking("k1");
        booking.expire(Instant.now());
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancel(1L, "too late"))
                .isInstanceOf(InvalidBookingStateException.class)
                .hasMessageContaining("EXPIRED");
    }

    @Test
    void aHoldCannotBeExpiredBeforeItsDeadline() {
        Booking booking = savedBooking("k1");
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.expire(1L))
                .isInstanceOf(InvalidBookingStateException.class)
                .hasMessageContaining("still held until");
    }

    @Test
    void anOverdueHoldIsExpiredAndSwept() {
        Booking overdue = new Booking(42L, 7L, 3, new BigDecimal("250.00"), "k1",
                Instant.now().minus(Duration.ofHours(1)),
                Instant.now().minus(Duration.ofMinutes(5)));
        when(bookings.findByStatusAndExpiresAtBefore(eq(BookingStatus.PENDING_PAYMENT), any()))
                .thenReturn(java.util.List.of(overdue));
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.expireOverdue()).isEqualTo(1);
        assertThat(overdue.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        verify(transitions).save(any());
    }

    @Test
    void anUnknownBookingIsReportedAsNotFound() {
        when(bookings.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9L)).isInstanceOf(BookingNotFoundException.class);
    }


}
