package com.ticketflow.bookingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Holds seats while a payment is pending, then confirms, fails or expires the booking.
 * Seat counts themselves belong to event-service, so the booking validates the event
 * through the OpenFeign {@code EventClient} (resolved by Eureka, never by a fixed URL).
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
