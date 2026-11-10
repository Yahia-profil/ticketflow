package com.ticketflow.bookingservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Internal HTTP client for event-service. The name matches the remote
 * {@code spring.application.name}, so Eureka and Spring Cloud LoadBalancer resolve it.
 * {@code ticketflow.clients.event-service.url} is empty by default: set
 * {@code EVENT_SERVICE_URL} only to bypass Eureka while working on a single service.
 */
@FeignClient(name = "event-service", url = "${ticketflow.clients.event-service.url:}")
public interface EventClient {

    @GetMapping("/api/events/{id}")
    EventResponse getById(@PathVariable("id") Long id);
}
