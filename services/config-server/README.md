# config-server

> Centralised configuration for TicketFlow: one Git repo, one HTTP endpoint, every service in sync.

## Concept illustrated
Spring Cloud Config Server reads a Git repository and exposes its YAML/properties
files over HTTP. Clients (`customer-service`, `api-gateway`) import the endpoint
at startup and receive their configuration before the application context is built.

**Three distinct flows coexist:**

| Flow | Path |
|---|---|
| Configuration | GitHub → Config Server :8888 → applications |
| Service discovery | Gateway + customer-service ↔ Eureka :8761 |
| Business traffic | Client → Gateway :8080 → customer-service :8081 |

The Config Server is **not** a Eureka client in this step: its URL is known statically.
`discovery-server` keeps its configuration local to avoid a startup circular dependency.

## Overview
- **Port:** 8888
- **Git repo:** separate `ticketflow-config` repository (sibling of `ticketflow/`)
- **Branch:** `main`
- **Files served:**
  - `application.yml` — shared properties (Eureka URL)
  - `customer-service.yml` — port 8081, actuator refresh, demo message
  - `api-gateway.yml` — port 8080, explicit route `lb://customer-service`

## Run locally
Export the Git URI, then start:

    export CONFIG_GIT_URI=https://github.com/<your-user>/ticketflow-config.git
    ./mvnw -pl services/config-server spring-boot:run

Verify:

    curl -i http://localhost:8888/actuator/health
    curl -i http://localhost:8888/customer-service/default/main
    curl -i http://localhost:8888/api-gateway/default/main

Each response is a JSON environment: `name`, `profiles`, `label`, `version`
(the Git commit), and `propertySources` with the merged properties.

## Run with Docker
    docker compose -f infra/docker-compose.yml up --build

Compose passes `CONFIG_GIT_URI` from the host environment.

## Hot refresh demo
1. Change `ticketflow.message` in `ticketflow-config/customer-service.yml`
2. `git push`
3. `curl :8888/customer-service/default/main` → new value visible
4. `curl :8081/api/config/message` → **still old value**
5. `POST :8081/actuator/refresh` → returns changed keys
6. `curl :8081/api/config/message` → **new value** ✅

## Common pitfalls
- **`CONFIG_GIT_URI` not set** → the server refuses to start.
- **Files not pushed to GitHub** → Config Server only sees what is on the remote.
- **Wrong file name** → must match `spring.application.name` exactly.
- **`optional:` in `spring.config.import`** → clients silently start without config; avoid it in production.
- **`POST :8081/actuator/refresh` returns 500** (`NullPointerException` in `updateInstanceRemoteStatus`) → Spring Cloud rebinds `@ConfigurationProperties` beans to class defaults first, which erases `eureka.instance.instance-id`. Keep Eureka beans out of the rebinder with `spring.cloud.refresh.never-refreshable` (already set in both clients).
- **Port/route changes** need a restart; only `@RefreshScope` beans update on `/actuator/refresh`.
