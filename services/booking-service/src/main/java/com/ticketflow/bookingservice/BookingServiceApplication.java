package com.ticketflow.bookingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Holds seats while a payment is pending, then confirms, fails or expires the booking.
 * Seat counts themselves belong to event-service; the OpenFeign client that talks to it
 * is added in the OpenFeign step of the roadmap.
 */
@SpringBootApplication
@EnableScheduling
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
