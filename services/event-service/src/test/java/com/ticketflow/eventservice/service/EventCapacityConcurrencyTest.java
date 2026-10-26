package com.ticketflow.eventservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.eventservice.dto.AvailabilityResponse;
import com.ticketflow.eventservice.dto.EventRequest;
import com.ticketflow.eventservice.dto.SeatRequest;
import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.exceptions.CapacityUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The capacity lives in event-service, so the check and the update must happen in a single
 * UPDATE statement. This test hammers one event from many threads to prove the seat counter
 * never goes below zero and never sells the same seat twice (FR-008).
 */
@SpringBootTest(properties = {
    "spring.config.import=optional:configserver:http://localhost:8888",
    "spring.cloud.config.enabled=false",
    "eureka.client.enabled=false",
    "server.port=0",
    "management.endpoints.web.exposure.include=health,info"
})
class EventCapacityConcurrencyTest {

    private static final int CAPACITY = 10;
    private static final int THREADS = 24;

    @Autowired
    EventService service;

    @Test
    void concurrentReservationsNeverOversellTheEvent() throws Exception {
        Event event = service.create(new EventRequest(
                "Concurrency", null, "CONCERT", "Arena", "Rabat",
                Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(2, ChronoUnit.DAYS),
                CAPACITY, new BigDecimal("100.00")));
        service.publish(event.getId());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger taken = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                String key = "thread-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.reserve(event.getId(), new SeatRequest(1, key));
                        taken.incrementAndGet();
                    } catch (CapacityUnavailableException ex) {
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        AvailabilityResponse availability = service.availability(event.getId());

        assertThat(taken.get()).isEqualTo(CAPACITY);
        assertThat(refused.get()).isEqualTo(THREADS - CAPACITY);
        assertThat(availability.reservedCapacity()).isEqualTo(CAPACITY);
        assertThat(availability.availableCapacity()).isZero();
    }
}
