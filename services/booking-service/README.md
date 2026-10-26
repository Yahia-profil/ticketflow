# booking-service

> Reservation lifecycle: temporary holds, simulated payment results and auditable transitions.

## Concept illustrated
A service whose data changes over time: a booking is not a row, it is a **state machine**.
The valid transitions are declared in one place, every change is written to an audit table,
and a hold on the seats expires on its own after a configurable delay.

## Overview
- **Port:** 8083
- **Database:** H2 in-memory (recreated on every start)
- **Depends on:** Config Server (8888) for its configuration; Eureka is registered but the
  calls to event-service arrive with the OpenFeign step of the roadmap

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

## Known gap (next step of the roadmap)
The seats themselves are owned by `event-service`: **the OpenFeign client that
validates the catalogue, takes the seats on creation and gives them back on decline,
expiry or cancellation is not wired yet**. Until then `unitPrice` comes from the request
and a booking never fails because the event is full. That is the OpenFeign commit.

## Package layout
    controllers/   REST endpoints
    service/       state machine, idempotency, expiry sweep
    repos/         Spring Data repositories
    entities/      Booking + BookingTransition
    dto/           request/response records (the API contract)
    exceptions/    domain exceptions and their HTTP mapping

## Run locally
    # Config Server must be running: ./mvnw -pl services/config-server spring-boot:run
    ./mvnw -pl services/booking-service spring-boot:run

    curl -i -X POST localhost:8083/api/bookings \
      -H 'Content-Type: application/json' \
      -d '{"eventId":1,"customerId":42,"quantity":2,"unitPrice":250.00,
           "idempotencyKey":"demo-1"}'

    curl -X POST localhost:8083/api/bookings/1/payment \
      -H 'Content-Type: application/json' -d '{"outcome":"APPROVED"}'
    curl localhost:8083/api/bookings/1/history

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
