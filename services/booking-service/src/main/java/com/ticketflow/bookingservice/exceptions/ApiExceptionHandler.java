package com.ticketflow.bookingservice.exceptions;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Same RFC 9457 mapping as the other services. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BookingNotFoundException.class)
    ProblemDetail handleNotFound(BookingNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** event-service answered 404: the event does not exist, the remote status is kept. */
    @ExceptionHandler(FeignException.NotFound.class)
    ProblemDetail handleRemoteNotFound(FeignException.NotFound ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "The requested event does not exist");
    }

    /**
     * Connection refused, timeout or a 5xx from event-service: the booking cannot be
     * validated, so the caller gets an explicit 503 instead of a fictitious success.
     */
    @ExceptionHandler({RetryableException.class, FeignException.class})
    ProblemDetail handleRemoteFailure(FeignException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "event-service is unavailable, the reservation was not created");
    }

    /**
     * The circuit is OPEN: the call is refused locally, without any network access. There
     * is deliberately no fallback that would pretend the event is available.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    ProblemDetail handleOpenCircuit(CallNotPermittedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "event-service circuit is open, the reservation was not created");
    }

    @ExceptionHandler({InvalidBookingStateException.class, IdempotencyConflictException.class,
            EventNotBookableException.class})
    ProblemDetail handleConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
