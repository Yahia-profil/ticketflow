package com.ticketflow.bookingservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Internal HTTP client for event-service. The name must match the remote
 * {@code spring.application.name}; Eureka and Spring Cloud LoadBalancer turn it into an
 * instance, so there is deliberately no {@code url} attribute (and no Gateway hop).
 */
@FeignClient(name = "event-service")
public interface EventClient {

    @GetMapping("/api/events/{id}")
    EventResponse getById(@PathVariable("id") Long id);
}
