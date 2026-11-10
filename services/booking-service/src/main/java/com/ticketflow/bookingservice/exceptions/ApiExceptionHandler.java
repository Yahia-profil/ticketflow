package com.ticketflow.bookingservice.exceptions;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Same RFC 9457 mapping as the other services for local business errors. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BookingNotFoundException.class)
    ProblemDetail handleNotFound(BookingNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** event-service answered 404: the event does not exist, the remote status is kept. */
    @ExceptionHandler(FeignException.NotFound.class)
    ResponseEntity<Void> remoteNotFound() {
        return ResponseEntity.notFound().build();
    }

    /**
     * Connection refused, timeout or an open circuit: the reservation is not created and
     * the caller gets an explicit 503. No fallback ever pretends the event is available.
     */
    @ExceptionHandler({RetryableException.class, CallNotPermittedException.class})
    ResponseEntity<Void> remoteUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler({InvalidBookingStateException.class, IdempotencyConflictException.class})
    ProblemDetail handleConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
