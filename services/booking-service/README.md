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
`GET /api/events/{id}`). It is injected in `BookingService`, never in the controller. The
name is resolved by Eureka and Spring Cloud LoadBalancer; the call never goes through the
Gateway. `ticketflow.clients.event-service.url` is empty by default (`EVENT_SERVICE_URL`
only exists to bypass Eureka when working on a single service).

`create()` calls it **before** the idempotency lookup and before any write, exactly as the
handout asks: when the call succeeds the existing logic runs; a remote error is never
turned into a reservation. The reservation body keeps its `unitPrice` field, the contract
of the previous step is not redefined.

| Remote outcome | HTTP returned by booking-service |
|---|---|
| `200` | `201`, or `200` on an idempotent replay |
| `404` (`FeignException.NotFound`) | `404`, empty body, never a success |
| connection refused, timeout (`RetryableException`) | `503`, empty body, no booking written |
| circuit open (`CallNotPermittedException`) | `503`, empty body, no booking written |

Timeouts are bounded (`connect-timeout=2000`, `read-timeout=3000` in
`spring.cloud.openfeign.client.config.default`, overridable from the Config Server): a slow
producer must not freeze the reservation flow.

## Circuit breaker (TP5)
The Feign call is wrapped in a Resilience4J circuit breaker
(`spring.cloud.openfeign.circuitbreaker.enabled=true`, one circuit per client thanks to
`group.enabled=true`). The configured instance is `EventClientgetByIdLong`, the name
generated for `EventClient#getById(Long)` as in the handout.

| State | Behaviour |
|---|---|
| CLOSED | calls go through, failures counted in a 10-call sliding window |
| OPEN | at 50% failures over at least 5 calls, calls are refused locally (no network) |
| HALF_OPEN | after 10 s, 3 test calls decide whether it closes again |

Thresholds live in the Config Server
(`resilience4j.circuitbreaker.instances.EventClientgetByIdLong.*`): window 10, minimum 5
calls, 50 %, 10 s open, 3 half-open calls, 4 s time limit.

`FeignException.NotFound` is a legitimate answer, not a failure: a series of 404s never
opens the circuit. Connection refused, timeouts and 5xx do. This has to be declared
explicitly (`ignore-exceptions` lists `feign.FeignException$NotFound` and
`feign.FeignException$BadRequest`), because Resilience4J counts every other exception.

| Situation | HTTP |
|---|---|
| event missing (404 from event-service) | `404`, empty body |
| event-service down or slow (`RetryableException`) | `503`, empty body |
| circuit open (`CallNotPermittedException`) | `503`, empty body |

There is **no fallback**: a broken event-service never produces a fictitious booking.

### Demo of the opening
    # event-service stopped: the 10 calls fail remotely, the circuit opens, then the calls
    # are rejected locally. The same body may be repeated thanks to the idempotency key.
    for i in $(seq 1 10); do
      curl -sS -o /dev/null -w "$i HTTP %{http_code} %{time_total}s\n" \
        -X POST localhost:8083/api/bookings -H 'Content-Type: application/json' \
        -d '{"eventId":1,"customerId":42,"quantity":1,"unitPrice":250.00,
             "idempotencyKey":"cb-1"}'
    done
    curl localhost:8083/actuator/circuitbreakers

    # 404s are ignored: repeat an unknown eventId five times, then book a real event.
    # The circuit stays CLOSED and the reservation still returns 201.

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

    # eventId must exist in event-service.
    curl -i -X POST localhost:8083/api/bookings \
      -H 'Content-Type: application/json' \
      -d '{"eventId":1,"customerId":42,"quantity":2,"unitPrice":250.00,
           "idempotencyKey":"demo-1"}'

    curl -X POST localhost:8083/api/bookings/1/payment \
      -H 'Content-Type: application/json' -d '{"outcome":"APPROVED"}'
    curl localhost:8083/api/bookings/1/history

    # unknown event -> 404 ; event-service stopped -> 503
    curl -i -X POST localhost:8083/api/bookings \
      -H 'Content-Type: application/json' \
      -d '{"eventId":999999,"customerId":42,"quantity":1,"unitPrice":250.00}'

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
