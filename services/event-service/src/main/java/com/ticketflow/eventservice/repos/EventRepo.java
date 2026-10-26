package com.ticketflow.eventservice.repos;

import com.ticketflow.eventservice.entities.Event;
import com.ticketflow.eventservice.entities.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepo extends JpaRepository<Event, Long> {

    Page<Event> findByStatus(EventStatus status, Pageable pageable);

    /**
     * Public catalogue search (FR-002): the text, city and category filters are optional and
     * combined with AND. Only the events in the requested status are returned.
     */
    @Query("""
            select e from Event e
             where e.status = :status
               and (:city is null or lower(e.city) like lower(concat('%', :city, '%')))
               and (:category is null or lower(e.category) like lower(concat('%', :category, '%')))
               and (:q is null
                    or lower(e.title) like lower(concat('%', :q, '%'))
                    or lower(e.description) like lower(concat('%', :q, '%')))
            """)
    Page<Event> search(@Param("status") EventStatus status,
                       @Param("city") String city,
                       @Param("category") String category,
                       @Param("q") String q,
                       Pageable pageable);

    /**
     * Reserves seats with a single atomic UPDATE: the row can only change when the extra
     * seats still fit in the total capacity of a published event (FR-008). The method
     * returns 0 when the request cannot be honoured, never a negative capacity.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Event e
               set e.reservedCapacity = e.reservedCapacity + :quantity
             where e.id = :id
               and e.status = :status
               and e.reservedCapacity + :quantity <= e.totalCapacity
            """)
    int reserveSeats(@Param("id") Long id,
                     @Param("quantity") int quantity,
                     @Param("status") EventStatus status);

    /** Gives seats back; the guard prevents the reserved capacity from going below zero. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Event e
               set e.reservedCapacity = e.reservedCapacity - :quantity
             where e.id = :id
               and e.reservedCapacity >= :quantity
            """)
    int releaseSeats(@Param("id") Long id, @Param("quantity") int quantity);
}
