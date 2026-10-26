package com.ticketflow.eventservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.eventservice.dto.AvailabilityResponse;
import com.ticketflow.eventservice.dto.EventRequest;
import com.ticketflow.eventservice.dto.SeatRequest;
import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.entities.EventStatus;
import com.ticketflow.eventservice.exceptions.CapacityUnavailableException;
import com.ticketflow.eventservice.exceptions.EventNotFoundException;
import com.ticketflow.eventservice.exceptions.InvalidEventStateException;
import com.ticketflow.eventservice.repos.EventRepo;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    EventRepo repo;

    @InjectMocks
    EventService service;

    private EventRequest request(int capacity) {
        return new EventRequest(
                "Concert", "Soiree", "MUSIC", "Arena", "Casablanca",
                Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(2, ChronoUnit.DAYS),
                capacity, new BigDecimal("250.00"));
    }

    private Event event(Long id, int capacity) {
        return new Event("Concert", "Soiree", "MUSIC", "Arena", "Casablanca",
                Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(2, ChronoUnit.DAYS),
                capacity, new BigDecimal("250.00"));
    }

    @Test
    void createSavesADraft() {
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        Event saved = service.create(request(50));

        assertThat(saved.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(saved.getTotalCapacity()).isEqualTo(50);
        verify(repo).save(any(Event.class));
    }

    @Test
    void createRejectsAnInvertedDateRange() {
        EventRequest invalid = new EventRequest(
                "Concert", null, "MUSIC", "Arena", "Casablanca",
                Instant.now().plus(2, ChronoUnit.DAYS),
                Instant.now().plus(1, ChronoUnit.DAYS),
                10, BigDecimal.TEN);

        assertThatThrownBy(() -> service.create(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startsAt");
    }

    @Test
    void publicReadHidesDrafts() {
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L, 10)));

        assertThatThrownBy(() -> service.getPublic(1L))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void closingADraftIsRefused() {
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L, 10)));

        assertThatThrownBy(() -> service.close(1L))
                .isInstanceOf(InvalidEventStateException.class)
                .hasMessageContaining("draft");
    }

    @Test
    void archivingADraftIsRefused() {
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L, 10)));

        assertThatThrownBy(() -> service.archive(1L))
                .isInstanceOf(InvalidEventStateException.class)
                .hasMessageContaining("draft");
    }

    @Test
    void archiveRemovesTheEventFromThePublicCatalogue() {
        Event closed = event(1L, 10);
        closed.close();
        when(repo.findById(1L)).thenReturn(Optional.of(closed));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.archive(1L).getStatus()).isEqualTo(EventStatus.ARCHIVED);
        assertThatThrownBy(() -> service.getPublic(1L)).isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void publishThenCloseMovesTheEventThroughItsLifecycle() {
        Event event = event(1L, 10);
        when(repo.findById(1L)).thenReturn(Optional.of(event));
        when(repo.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.publish(1L).getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(service.close(1L).getStatus()).isEqualTo(EventStatus.CLOSED);
    }

    @Test
    void reserveIsRefusedWhenTheAtomicUpdateMatchesNoRow() {
        Event event = event(1L, 10);
        event.publish();
        when(repo.findById(1L)).thenReturn(Optional.of(event));
        when(repo.reserveSeats(1L, 4, EventStatus.PUBLISHED)).thenReturn(0);

        assertThatThrownBy(() -> service.reserve(1L, new SeatRequest(4, null)))
                .isInstanceOf(CapacityUnavailableException.class);
    }

    @Test
    void reserveIsRefusedWhenTheEventIsNotBookable() {
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L, 10)));

        assertThatThrownBy(() -> service.reserve(1L, new SeatRequest(1, null)))
                .isInstanceOf(InvalidEventStateException.class);
    }

    @Test
    void reserveReturnsTheAvailability() {
        Event event = event(1L, 10);
        event.publish();
        when(repo.findById(1L)).thenReturn(Optional.of(event));
        when(repo.reserveSeats(1L, 4, EventStatus.PUBLISHED)).thenReturn(1);

        AvailabilityResponse availability = service.reserve(1L, new SeatRequest(4, null));

        assertThat(availability.status()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(availability.totalCapacity()).isEqualTo(10);
    }

    @Test
    void releaseFailsWhenThereIsNothingToGiveBack() {
        when(repo.findById(1L)).thenReturn(Optional.of(event(1L, 10)));
        when(repo.releaseSeats(1L, 2)).thenReturn(0);

        assertThatThrownBy(() -> service.release(1L, new SeatRequest(2, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchQueriesThePublishedEventsOnly() {
        when(repo.search(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        assertThat(service.search(null, null, null, Pageable.unpaged()).getContent()).isEmpty();
        verify(repo).search(EventStatus.PUBLISHED, null, null, null, Pageable.unpaged());
    }
}
