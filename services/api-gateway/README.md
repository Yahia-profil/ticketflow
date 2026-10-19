# api-gateway

> Single entry point of TicketFlow: clients talk to the gateway, never to the services directly.

## Concept illustrated
Spring Cloud Gateway (WebFlux flavour) as a reverse proxy. A **route** =
an id + a target `uri` + **predicates** (when does it match?) + **filters**
(what do we change on the way?).

In this step the target uses the **`lb://` scheme**: the gateway asks Eureka
for an instance of `customer-service` and Spring Cloud LoadBalancer picks one.
The route declaration itself is unchanged from the static version — only the
way the address is resolved moved from "written down" to "discovered".

## Overview
- **Port:** 8080
- **Depends on:** `discovery-server` (8761) and `customer-service` (8081)
- **Runtime:** WebFlux on Netty (not Tomcat)

| Route | Predicate | Target |
|---|---|---|
| `customer-service` | `Path=/api/customers/**` | `lb://customer-service` |

Filter used: `AddResponseHeader=X-Served-By, api-gateway`.

`lb://customer-service` means: look up `customer-service` in the Eureka
registry and let LoadBalancer choose one instance. The registry URL is
configured with `eureka.client.service-url.defaultZone`.

## Run locally
Start Eureka first, then the business services, then the gateway:

    # Terminal 1 — registry
    ./mvnw -pl services/discovery-server spring-boot:run

    # Terminal 2 — business service
    ./mvnw -pl services/customer-service spring-boot:run

    # Terminal 3 — gateway
    ./mvnw -pl services/api-gateway spring-boot:run

Call customer-service **through the gateway** (port 8080, not 8081):

    curl -i -X POST localhost:8080/api/customers \
      -H 'Content-Type: application/json' \
      -d '{"firstName":"Sara","lastName":"Idrissi","email":"sara@example.com"}'

    curl -i localhost:8080/api/customers

Look for the `X-Served-By: api-gateway` response header. List the routes the
gateway knows about:

    curl localhost:8080/actuator/gateway/routes

The `uri` field should read `lb://customer-service`. On the Eureka dashboard
(<http://localhost:8761>) both `API-GATEWAY` and `CUSTOMER-SERVICE` appear as UP.

## Run with Docker
    docker compose -f infra/docker-compose.yml up --build

## Run the tests
    ./mvnw -pl services/api-gateway test

The test starts the gateway and a stub backend, so Eureka and
`customer-service` do not need to run. The test overrides the target URI
through the `customer-service.url` property.

## How the gateway finds its target
1. On startup the gateway registers itself with Eureka as `api-gateway`.
2. Every ~30 s it fetches the registry and caches the list of instances.
3. When a request matches `Path=/api/customers/**`, LoadBalancer picks one
   `customer-service` instance from the cache and forwards the HTTP call
   **directly** to it (Eureka is not on the data path).

## Common pitfalls
- Spring Cloud 2025.1 renamed the properties: routes live under
  `spring.cloud.gateway.server.webflux.routes`. Old tutorials using
  `spring.cloud.gateway.routes` silently do nothing.
- Never add `spring-boot-starter-web` (Tomcat/MVC) next to the WebFlux gateway:
  the two models conflict and the application fails to start.
- A 404 from the gateway means no route matched; a 5xx (502/503) means a route
  matched but the backend is unreachable — or, right after startup, the registry
  cache is still empty.
- Inside Docker, `localhost` is the container itself; the Compose file sets
  `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` to point at the `discovery-server`
  service name.
