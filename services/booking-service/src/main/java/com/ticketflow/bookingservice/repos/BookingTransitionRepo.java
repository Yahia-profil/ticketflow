package com.ticketflow.bookingservice.repos;

import com.ticketflow.bookingservice.entities.BookingTransition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingTransitionRepo extends JpaRepository<BookingTransition, Long> {

    List<BookingTransition> findByBookingIdOrderByAtAsc(Long bookingId);
}
