package com.ticketflow.eventservice.service;

import static com.ticketflow.eventservice.entities.EventStatus.ARCHIVED;
import static com.ticketflow.eventservice.entities.EventStatus.DRAFT;
import static com.ticketflow.eventservice.entities.EventStatus.PUBLISHED;

import com.ticketflow.eventservice.dto.AvailabilityResponse;
import com.ticketflow.eventservice.dto.EventRequest;
import com.ticketflow.eventservice.dto.SeatRequest;
import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.exceptions.CapacityUnavailableException;
import com.ticketflow.eventservice.exceptions.EventNotFoundException;
import com.ticketflow.eventservice.exceptions.InvalidEventStateException;
import com.ticketflow.eventservice.repos.EventRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EventService {

    private final EventRepo repo;

    public EventService(EventRepo repo) {
        this.repo = repo;
    }

    public Event create(EventRequest request) {
        validate(request);
        Event event = new Event(
                request.title().trim(),
                request.description(),
                request.category().trim(),
                request.venue().trim(),
                request.city().trim(),
                request.startsAt(),
                request.endsAt(),
                request.totalCapacity(),
                request.ticketPrice());
        return repo.save(event);
    }

    /** Public read: a draft or an archived event must not be exposed (FR-003). */
    @Transactional(readOnly = true)
    public Event getPublic(Long id) {
        Event event = findManaged(id);
        if (event.getStatus() == DRAFT || event.getStatus() == ARCHIVED) {
            throw new EventNotFoundException(id);
        }
        return event;
    }

    @Transactional(readOnly = true)
    public Event findManaged(Long id) {
        return repo.findById(id).orElseThrow(() -> new EventNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Event> search(String city, String category, String q, Pageable pageable) {
        return repo.search(PUBLISHED, emptyToNull(city), emptyToNull(category), emptyToNull(q), pageable);
    }

    public Event update(Long id, EventRequest request) {
        validate(request);
        Event event = findManaged(id);
        if (request.totalCapacity() < event.getReservedCapacity()) {
            throw new IllegalArgumentException(
                    "totalCapacity cannot be lowered below the " + event.getReservedCapacity()
                            + " seats already reserved");
        }
        event.update(
                request.title().trim(),
                request.description(),
                request.category().trim(),
                request.venue().trim(),
                request.city().trim(),
                request.startsAt(),
                request.endsAt(),
                request.totalCapacity(),
                request.ticketPrice());
        return repo.save(event);
    }

    public Event publish(Long id) {
        Event event = findManaged(id);
        if (event.getStatus() == ARCHIVED) {
            throw new InvalidEventStateException("An archived event cannot be published again");
        }
        event.publish();
        return repo.save(event);
    }

    public Event close(Long id) {
        Event event = findManaged(id);
        if (event.getStatus() == DRAFT) {
            throw new InvalidEventStateException("A draft must be published before it can be closed");
        }
        event.close();
        return repo.save(event);
    }

    public Event archive(Long id) {
        Event event = findManaged(id);
        if (event.getStatus() == DRAFT) {
            throw new InvalidEventStateException("A draft must be published before it can be archived");
        }
        event.archive();
        return repo.save(event);
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse availability(Long id) {
        return AvailabilityResponse.from(findManaged(id));
    }

    /**
     * Atomically takes seats for a booking. The capacity belongs to this service, so the
     * check and the update happen in one UPDATE statement (FR-008).
     */
    public AvailabilityResponse reserve(Long id, SeatRequest request) {
        Event event = findManaged(id);
        if (!event.isBookable()) {
            throw new InvalidEventStateException(
                    "Event " + id + " is not open for booking (status " + event.getStatus() + ")");
        }
        int updated = repo.reserveSeats(id, request.quantity(), PUBLISHED);
        if (updated == 0) {
            throw new CapacityUnavailableException(id, request.quantity());
        }
        return AvailabilityResponse.from(findManaged(id));
    }

    public AvailabilityResponse release(Long id, SeatRequest request) {
        findManaged(id);
        int updated = repo.releaseSeats(id, request.quantity());
        if (updated == 0) {
            throw new IllegalArgumentException(
                    "Cannot release " + request.quantity() + " seats on event " + id);
        }
        return AvailabilityResponse.from(findManaged(id));
    }

    private void validate(EventRequest request) {
        if (!request.startsAt().isBefore(request.endsAt())) {
            throw new IllegalArgumentException("startsAt must be before endsAt");
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
