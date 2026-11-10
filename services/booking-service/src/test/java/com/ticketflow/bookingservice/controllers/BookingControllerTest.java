package com.ticketflow.bookingservice.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketflow.bookingservice.clients.EventClient;
import com.ticketflow.bookingservice.clients.EventResponse;
import com.ticketflow.bookingservice.dto.BookingRequest;
import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.config.import=optional:configserver:http://localhost:8888",
    "spring.cloud.config.enabled=false",
    "eureka.client.enabled=false",
    "server.port=0",
    "management.endpoints.web.exposure.include=health,info"
})
@AutoConfigureMockMvc
class BookingControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    /** The remote catalogue is replaced by a stub: these tests never call the network. */
    @MockitoBean
    EventClient eventClient;

    @BeforeEach
    void stubTheRemoteCatalogue() {
        lenient().when(eventClient.getById(1L)).thenReturn(event(1L, "PUBLISHED", "250.00"));
        lenient().when(eventClient.getById(2L)).thenReturn(event(2L, "CLOSED", "250.00"));
        lenient().when(eventClient.getById(3L)).thenThrow(mock(RetryableException.class));
        lenient().when(eventClient.getById(4L)).thenThrow(mock(CallNotPermittedException.class));
        lenient().when(eventClient.getById(999L)).thenThrow(mock(FeignException.NotFound.class));
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private BookingRequest request(Long eventId, Long customerId, int quantity, String key) {
        return new BookingRequest(eventId, customerId, quantity, key);
    }

    private EventResponse event(Long id, String status, String price) {
        Instant startsAt = Instant.now().plus(Duration.ofDays(10));
        return new EventResponse(id, "Jazz night", "Live jazz", "CONCERT", "Opera", "Alger",
                startsAt, startsAt.plus(Duration.ofHours(3)), status,
                50, 0, 50, new BigDecimal(price), Instant.now());
    }

    private String createAndGetId(Long customerId, int quantity) throws Exception {
        MvcResult created = mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(1L, customerId, quantity, key()))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/bookings/")))
                .andReturn();
        return created.getResponse().getHeader("Location")
                .replaceAll(".*/api/bookings/", "");
    }

    @Test
    void aRepeatedIdempotencyKeyReturnsTheSameBooking() throws Exception {
        String key = key();
        BookingRequest body = request(1L, 42L, 2, key);

        MvcResult first = mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.unitPrice").value(250.00))
                .andReturn();

        MvcResult second = mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();

        String firstId = first.getResponse().getHeader("Location").replaceAll(".*/api/bookings/", "");
        String secondId = mapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void theSameKeyWithAnotherPayloadIsAConflict() throws Exception {
        String key = key();
        mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(1L, 42L, 2, key))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(1L, 42L, 5, key))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Idempotency key")));
    }

    @Test
    void thePaymentMovesTheBookingThroughItsStates() throws Exception {
        String id = createAndGetId(7L, 3);

        mvc.perform(post("/api/bookings/" + id + "/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"UNAVAILABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        mvc.perform(post("/api/bookings/" + id + "/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(post("/api/bookings/" + id + "/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("awaiting payment")));

        mvc.perform(post("/api/bookings/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"seats not needed anymore\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failureReason").value("seats not needed anymore"));
    }

    @Test
    void aDeclinedPaymentRecordsTheFailure() throws Exception {
        String id = createAndGetId(8L, 1);

        mvc.perform(post("/api/bookings/" + id + "/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"DECLINED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.failureReason").value(containsString("declined")));

        mvc.perform(post("/api/bookings/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void theAuditTrailShowsEveryChange() throws Exception {
        String id = createAndGetId(9L, 1);

        mvc.perform(get("/api/bookings/" + id + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].to").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$[0].reason").value("Booking created, seats held"));

        mvc.perform(post("/api/bookings/" + id + "/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/bookings/" + id + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].from").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$[1].to").value("CONFIRMED"));
    }

    @Test
    void aBookingIsListedByCustomer() throws Exception {
        String id = createAndGetId(55L, 4);

        mvc.perform(get("/api/bookings").param("customerId", "55"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(Long.parseLong(id)))
                .andExpect(jsonPath("$[0].customerId").value(55));

        mvc.perform(get("/api/bookings").param("customerId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void anUnknownEventIsReportedAsNotFound() throws Exception {
        mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(999L, 77L, 1, key()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("event")));
    }

    @Test
    void anEventThatIsNotOnSaleIsAConflict() throws Exception {
        mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(2L, 78L, 1, key()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("CLOSED")));
    }

    @Test
    void anUnreachableEventServiceIsA503AndCreatesNothing() throws Exception {
        mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(3L, 79L, 1, key()))))
                .andExpect(status().isServiceUnavailable());

        mvc.perform(get("/api/bookings").param("customerId", "79"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void anOpenCircuitIsA503AndCreatesNothing() throws Exception {
        mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(4L, 80L, 1, key()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("circuit is open")));

        mvc.perform(get("/api/bookings").param("customerId", "80"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void unknownBookingsAndInvalidBodiesAreRejected() throws Exception {
        mvc.perform(get("/api/bookings/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/999999/history")).andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/999999/cancel")).andExpect(status().isNotFound());

        mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":1,\"customerId\":1,\"quantity\":0}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":1,\"quantity\":1}"))
                .andExpect(status().isBadRequest());

        String id = createAndGetId(3L, 1);
        mvc.perform(post("/api/bookings/" + id + "/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }
}
