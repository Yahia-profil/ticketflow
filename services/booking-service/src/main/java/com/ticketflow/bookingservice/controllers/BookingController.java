package com.ticketflow.bookingservice.controllers;

import com.ticketflow.bookingservice.dto.BookingRequest;
import com.ticketflow.bookingservice.dto.BookingResponse;
import com.ticketflow.bookingservice.dto.CancelRequest;
import com.ticketflow.bookingservice.dto.PaymentRequest;
import com.ticketflow.bookingservice.dto.TransitionResponse;

import com.ticketflow.bookingservice.service.BookingService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    /**
     * Creates a hold on the seats. A repeated idempotency key returns the original booking
     * with 200 instead of creating a second one (rule of management 5).
     */
    @PostMapping
    ResponseEntity<BookingResponse> create(@Valid @RequestBody BookingRequest request) {
        BookingService.CreateResult result = service.create(request);
        BookingResponse body = BookingResponse.from(result.booking());
        if (result.replayed()) {
            return ResponseEntity.ok(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(result.booking().getId()).toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{id}")
    BookingResponse get(@PathVariable Long id) {
        return BookingResponse.from(service.get(id));
    }

    @GetMapping
    List<BookingResponse> list(@RequestParam(required = false) Long customerId) {
        return service.list(customerId).stream().map(BookingResponse::from).toList();
    }

    /** Full audit trail of one booking: every status change with its reason. */
    @GetMapping("/{id}/history")
    List<TransitionResponse> history(@PathVariable Long id) {
        return service.history(id);
    }

    /** Feeds the result of the simulated payment to the booking (RF-05). */
    @PostMapping("/{id}/payment")
    BookingResponse pay(@PathVariable Long id, @Valid @RequestBody PaymentRequest request) {
        return BookingResponse.from(service.pay(id, request));
    }

    @PostMapping("/{id}/cancel")
    BookingResponse cancel(@PathVariable Long id,
                           @RequestBody(required = false) CancelRequest request) {
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "Cancelled by the customer"
                : request.reason().trim();
        return BookingResponse.from(service.cancel(id, reason));
    }

    /** Manual expiry, useful for demos; the same rule runs on a schedule. */
    @PostMapping("/{id}/expire")
    BookingResponse expire(@PathVariable Long id) {
        return BookingResponse.from(service.expire(id));
    }
}
