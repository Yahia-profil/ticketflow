package com.ticketflow.eventservice.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketflow.eventservice.dto.EventRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class EventControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private EventRequest request(int capacity, String title) {
        return new EventRequest(
                title, "Une soirÃƒÆ’Ã‚Â©e", "CONCERT", "Arena", "Casablanca",
                Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(2, ChronoUnit.DAYS),
                capacity, new BigDecimal("250.00"));
    }

    private long createPublished(String title, int capacity) throws Exception {
        MvcResult created = mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(capacity, title))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/events/")))
                .andReturn();

        String location = created.getResponse().getHeader("Location");
        String id = location.substring(location.lastIndexOf('/') + 1);

        mvc.perform(post("/api/events/" + id + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        return Long.parseLong(id);
    }

    @Test
    void crudLifecycleFromDraftToArchived() throws Exception {
        MvcResult created = mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(30, "Lifecycle"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();

        String location = created.getResponse().getHeader("Location");
        String id = location.substring(location.lastIndexOf('/') + 1);

        mvc.perform(get(location)).andExpect(status().isNotFound());

        mvc.perform(post("/api/events/" + id + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mvc.perform(get("/api/events/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Lifecycle"));

        mvc.perform(put("/api/events/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(45, "Lifecycle (maj)"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCapacity").value(45));

        mvc.perform(post("/api/events/" + id + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mvc.perform(post("/api/events/" + id + "/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mvc.perform(get("/api/events/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void catalogueFiltersOnCityAndTextAndHidesDrafts() throws Exception {
        long visible = createPublished("Festival Jazz", 100);
        MvcResult draft = mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(10, "Secret Draft"))))
                .andExpect(status().isCreated())
                .andReturn();
        String draftLocation = draft.getResponse().getHeader("Location");

        mvc.perform(get("/api/events").param("city", "casablan").param("q", "jazz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(visible));

        mvc.perform(get("/api/events").param("q", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        mvc.perform(get("/api/events").param("city", "Rabat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        mvc.perform(get(draftLocation)).andExpect(status().isNotFound());
    }

    @Test
    void seatsAreReservedReleasedAndNeverOversold() throws Exception {
        long id = createPublished("Salle intime", 3);

        mvc.perform(post("/api/events/" + id + "/availability"))
                .andExpect(status().isMethodNotAllowed());

        mvc.perform(get("/api/events/" + id + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCapacity").value(3))
                .andExpect(jsonPath("$.availableCapacity").value(3));

        mvc.perform(post("/api/events/" + id + "/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2,\"idempotencyKey\":\"b1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCapacity").value(1));

        mvc.perform(post("/api/events/" + id + "/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/events/" + id + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCapacity").value(3));

        mvc.perform(post("/api/events/" + id + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void closedEventsAreNotBookable() throws Exception {
        long id = createPublished("SoirÃƒÆ’Ã‚Â©e close", 20);
        mvc.perform(post("/api/events/" + id + "/close")).andExpect(status().isOk());

        mvc.perform(post("/api/events/" + id + "/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateCannotCutCapacityBelowReservedSeats() throws Exception {
        long id = createPublished("Complet", 10);
        mvc.perform(post("/api/events/" + id + "/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":8}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/events/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(5, "Complet"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidBodiesAreRejected() throws Exception {
        Map<String, Object> noCapacity = new LinkedHashMap<>(Map.of(
                "title", "X",
                "category", "CONCERT",
                "venue", "Arena",
                "city", "Casablanca",
                "startsAt", "2026-12-01T20:00:00Z",
                "endsAt", "2026-12-01T23:00:00Z",
                "ticketPrice", 100));
        mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(noCapacity)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"X\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/events/999999/availability")).andExpect(status().isNotFound());
        mvc.perform(post("/api/events/999999/publish")).andExpect(status().isNotFound());
    }
}
