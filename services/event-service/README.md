# event-service

> Events catalogue and seat inventory of TicketFlow.

## Concept illustrated
A second business service with its own database, that owns a **shared resource**:
the seats of an event. It also plays the role of the *resource owner* that another
service will call later with OpenFeign (TP4): `reserve` and `release` are internal
endpoints, not meant for the browser.

## Overview
- **Port:** 8082
- **Database:** H2 in-memory (recreated on every start)
- **Depends on:** Config Server (8888) for its configuration; Eureka is registered when the
  discovery server is up. Start the infrastructure first (see `infra/docker-compose.yml`).

| Method | Path | Description |
|---|---|---|
| POST | `/api/events` | Create an event in `DRAFT` (201 + Location) |
| GET | `/api/events` | Public catalogue: `PUBLISHED` events, filtered and paginated |
| GET | `/api/events/{id}` | Get one event (404 for drafts and archived events) |
| PUT | `/api/events/{id}` | Update an event |
| POST | `/api/events/{id}/publish` | `DRAFT/CLOSED` â†’ `PUBLISHED` |
| POST | `/api/events/{id}/close` | `PUBLISHED` â†’ `CLOSED` |
| POST | `/api/events/{id}/archive` | Remove from the catalogue for good |
| GET | `/api/events/{id}/availability` | Remaining seats |
| POST | `/api/events/{id}/reserve` | **Internal:** take seats for a booking |
| POST | `/api/events/{id}/release` | **Internal:** give seats back |

Catalogue filters: `?city=`, `?category=`, `?q=` (title or description), `?page=`, `?size=`.

Errors follow RFC 9457 (`ProblemDetail`): 400 validation, 404 not found,
409 not enough seats / invalid transition.

## Lifecycle
    DRAFT --publish--> PUBLISHED --close--> CLOSED --archive--> ARCHIVED
                              \--archive--> ARCHIVED

Only `PUBLISHED` events are bookable and visible in the public catalogue.

## Package layout
    controllers/   REST endpoints
    service/       business logic (transitions, capacity rules)
    repos/         Spring Data repositories
    entities/      JPA entities
    dto/           request/response records (the API contract)
    exceptions/    domain exceptions and their HTTP mapping

## The seat counter
`totalCapacity` and `reservedCapacity` live in this service. Taking seats goes through
one atomic statement:

    @Modifying
    @Query("update Event e set e.reservedCapacity = e.reservedCapacity + :quantity, ...")

so two concurrent bookings can never oversell the same event; the capacity check and the
update happen inside the same row lock. `EventCapacityConcurrencyTest` proves it with
24 threads on a 10-seat event.

## Run locally
    ./mvnw -pl services/event-service spring-boot:run

    curl -i -X POST localhost:8082/api/events \
      -H 'Content-Type: application/json' \
      -d '{"title":"Concert","category":"CONCERT","venue":"Arena","city":"Casablanca",
           "startsAt":"2026-12-01T20:00:00Z","endsAt":"2026-12-01T23:00:00Z",
           "totalCapacity":100,"ticketPrice":250.00}'

    curl -X POST localhost:8082/api/events/1/publish
    curl localhost:8082/api/events/1/availability

## Run with Docker
    docker compose -f infra/docker-compose.yml up --build event-service

## Run the tests
    ./mvnw -pl services/event-service test

## Exercise
1. Call `POST /api/events/1/reserve` from two terminals at the same time with a
   quantity bigger than the remaining seats. What happens?
2. Add a `GET /api/events/{id}/seats` endpoint that returns the remaining seats as a
   `List<Integer>` of seat numbers.
3. `reservedCapacity` is only updated through JPQL. Why would saving the entity right
   after a reservation risk losing a concurrent update?

## Common pitfalls
- Exposing the entity would let callers read `reservedCapacity` while a reservation is
  being written: the API is a record (`EventResponse`), the entity is internal.
- The catalogue endpoint must filter on `status`, otherwise drafts leak before publication.
- `@Modifying` queries bypass the persistence context: always re-read the entity after
  an update, or the response would return stale values.
