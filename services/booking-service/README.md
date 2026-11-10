# booking-service

> Reservation lifecycle: temporary holds, simulated payment results and auditable transitions.

## Concept illustrated
A service whose data changes over time: a booking is not a row, it is a **state machine**.
The valid transitions are declared in one place, every change is written to an audit table,
and a hold on the seats expires on its own after a configurable delay.

## Overview
- **Port:** 8083
- **Database:** H2 in-memory (recreated on every start)
- **Depends on:** Config Server (8888) for its configuration, Eureka (8761) to resolve
  `event-service` by name, and event-service itself through the OpenFeign `EventClient`

| Method | Path | Description |
|---|---|---|
| POST | `/api/bookings` | Create a hold (201 + Location, 200 on an idempotent replay) |
| GET | `/api/bookings/{id}` | Get one booking |
| GET | `/api/bookings?customerId=` | List the bookings of a customer |
| GET | `/api/bookings/{id}/history` | Audit trail of every status change |
| POST | `/api/bookings/{id}/payment` | Feed the simulated payment result |
| POST | `/api/bookings/{id}/cancel` | Cancel a booking (optional `{"reason":"..."}`) |
| POST | `/api/bookings/{id}/expire` | Expire an overdue hold by hand |

Payment outcomes (`POST .../payment`, body `{"outcome":"..."}`):
- `APPROVED` → `CONFIRMED`
- `DECLINED` → `PAYMENT_FAILED`
- `UNAVAILABLE` → stays `PENDING_PAYMENT`, the call can be retried

## State machine
                payment approved
    PENDING_PAYMENT ──────────────► CONFIRMED
       │      │  │                    │
       │      │  └── cancel ──────────┼──► CANCELLED
       │      │                       │
       │      ├── DECLINED ──► PAYMENT_FAILED ──► CANCELLED
       │      │
       │      └── hold elapsed ──► EXPIRED
       └── cancel ──► CANCELLED

Anything else is a 409: a confirmed booking cannot be paid again, an expired booking is
terminal, a hold cannot be expired before its deadline.

## Rules implemented (cahier des charges)
- **Rule 2** — a booking holds seats only while the payment is pending; the delay is
  `ticketflow.booking.hold-duration` (ISO-8601, default `PT15M`) and a scheduled sweep
  (`ticketflow.booking.sweep-interval`, milliseconds, default `60000`) expires overdue holds.
- **Rule 3** — the five states above, transitions controlled here, each change recorded in
  `booking_transitions` with a reason and a timestamp.
- **Rule 5** — creation is idempotent: repeat the same `idempotencyKey` and you get the same
  booking back (200 instead of 201); the same key with a different payload is a 409.

## Remote call to event-service (TP4)
`EventClient` is a declarative OpenFeign client (`@FeignClient(name = "event-service")`,
`GET /api/events/{id}`). It is injected in `BookingService`, never in the controller, and it
carries **no `url`**: Eureka and Spring Cloud LoadBalancer resolve the service name, and the
call never goes through the Gateway.

`create()` calls it **before** writing anything:
- the price is not sent by the caller any more: `unitPrice` is read from the event's
  `ticketPrice`, so a booking can no longer carry an invented price;
- an event that is not `PUBLISHED` (draft, closed, archived) is refused with 409;
- a replayed `idempotencyKey` returns the stored booking **without** calling event-service.

| Remote outcome | HTTP returned by booking-service |
|---|---|
| `200` with `status=PUBLISHED` | `201` (or `200` on an idempotent replay) |
| `200` with another status | `409` `{"detail":"Event 12 is CLOSED ..."}` |
| `404` (Feign `FeignException.NotFound`) | `404`, never turned into a success |
| connection refused, timeout, `5xx` (`RetryableException`) | `503`, no booking written |

Timeouts are bounded (`connect-timeout=2000`, `read-timeout=3000` in
`spring.cloud.openfeign.client.config.default`, overridable from the Config Server): a slow
producer must not freeze the reservation flow.

## Package layout
    controllers/   REST endpoints
    clients/       OpenFeign client + read-only mirror of the event JSON
    service/       state machine, idempotency, expiry sweep
    repos/         Spring Data repositories
    entities/      Booking + BookingTransition
    dto/           request/response records (the API contract)
    exceptions/    domain exceptions and their HTTP mapping

## Run locally
    # Config Server and Eureka must be running, then event-service (8082):
    ./mvnw -pl services/config-server spring-boot:run
    ./mvnw -pl services/booking-service spring-boot:run

    # eventId must exist and be PUBLISHED in event-service.
    curl -i -X POST localhost:8083/api/bookings \
      -H 'Content-Type: application/json' \
      -d '{"eventId":1,"customerId":42,"quantity":2,"idempotencyKey":"demo-1"}'

    curl -X POST localhost:8083/api/bookings/1/payment \
      -H 'Content-Type: application/json' -d '{"outcome":"APPROVED"}'
    curl localhost:8083/api/bookings/1/history

    # unknown event -> 404 ; event-service stopped -> 503
    curl -i -X POST localhost:8083/api/bookings \
      -H 'Content-Type: application/json' \
      -d '{"eventId":999999,"customerId":42,"quantity":1}'

## Run with Docker
    docker compose -f infra/docker-compose.yml up --build booking-service

## Run the tests
    ./mvnw -pl services/booking-service test

## Exercise
1. POST the same body twice and compare the two responses: why does the second one return 200?
2. Set `ticketflow.booking.hold-duration=PT5S`, create a booking and wait: the scheduled
   sweep flips it to `EXPIRED`. Where would you record that the seats were given back?
3. What has to change when `UNAVAILABLE` becomes a real provider timeout?

## Common pitfalls
- A state machine written as scattered `if` statements drifts as soon as one caller is
  forgotten: the transitions live in a single map and are checked centrally.
- Expiry must be driven by a stored deadline, not by a counter in memory, or a restart
  would silently extend every hold.
- Idempotency needs a unique key in the database; checking it after inserting is already
  too late, which is why the column is unique and the lookup happens first.
