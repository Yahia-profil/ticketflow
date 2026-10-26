package com.ticketflow.eventservice.controllers;

import com.ticketflow.eventservice.dto.AvailabilityResponse;
import com.ticketflow.eventservice.dto.EventRequest;
import com.ticketflow.eventservice.dto.EventResponse;
import com.ticketflow.eventservice.dto.PageResponse;
import com.ticketflow.eventservice.dto.SeatRequest;
import com.ticketflow.eventservice.service.EventService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
        EventResponse created = EventResponse.from(service.create(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Public catalogue: published events only, filtered and paginated (FR-001, FR-002). */
    @GetMapping
    PageResponse<EventResponse> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.from(
                service.search(city, category, q, pageable).map(EventResponse::from));
    }

    @GetMapping("/{id}")
    EventResponse get(@PathVariable Long id) {
        return EventResponse.from(service.getPublic(id));
    }

    @PutMapping("/{id}")
    EventResponse update(@PathVariable Long id, @Valid @RequestBody EventRequest request) {
        return EventResponse.from(service.update(id, request));
    }

    @PostMapping("/{id}/publish")
    EventResponse publish(@PathVariable Long id) {
        return EventResponse.from(service.publish(id));
    }

    @PostMapping("/{id}/close")
    EventResponse close(@PathVariable Long id) {
        return EventResponse.from(service.close(id));
    }

    @PostMapping("/{id}/archive")
    EventResponse archive(@PathVariable Long id) {
        return EventResponse.from(service.archive(id));
    }

    @GetMapping("/{id}/availability")
    AvailabilityResponse availability(@PathVariable Long id) {
        return service.availability(id);
    }

    /** Internal contract consumed by booking-service through OpenFeign in TP4. */
    @PostMapping("/{id}/reserve")
    AvailabilityResponse reserve(@PathVariable Long id, @Valid @RequestBody SeatRequest request) {
        return service.reserve(id, request);
    }

    /** Gives seats back when a pending booking is expired, failed or cancelled. */
    @PostMapping("/{id}/release")
    AvailabilityResponse release(@PathVariable Long id, @Valid @RequestBody SeatRequest request) {
        return service.release(id, request);
    }
}
