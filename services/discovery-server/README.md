# discovery-server

> The service registry: every microservice announces itself here, and clients look each other up by logical name.

## Concept illustrated
Netflix **Eureka** as a service registry. Two roles coexist in the same jar:

- **Eureka Server** (this module) keeps the catalogue of live instances and serves it over REST on `/eureka/apps`.
- **Eureka Client** (the starter added to `customer-service` and `api-gateway`) registers on startup, sends heartbeats every ~30 s, and periodically fetches the registry.

Services never call Eureka for business traffic — the registry is a **control plane** only. Data still flows directly from the gateway to the chosen instance.

## Overview
- **Port:** 8761 (the conventional Eureka port)
- **Standalone configuration:** `register-with-eureka: false` and `fetch-registry: false` stop the server from registering with itself.

## Run locally
From the repository root:

    ./mvnw -pl services/discovery-server spring-boot:run

Open the dashboard: <http://localhost:8761>
Inspect the REST endpoint:

    curl -i -H 'Accept: application/json' http://localhost:8761/eureka/apps

## Run with Docker
    docker compose -f infra/docker-compose.yml up --build

Inside the Compose network the clients point to `http://discovery-server:8761/eureka/` through the `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` environment variable.

## How the other services connect
Both `customer-service` and `api-gateway` declare:

    eureka.client.service-url.defaultZone=http://localhost:8761/eureka/

`spring.application.name` becomes the logical service id used in `lb://customer-service` URIs.

## Common pitfalls
- **Forgot `register-with-eureka: false`**: the server tries to register with itself and logs noisy warnings.
- **Wrong `defaultZone` inside Docker**: `localhost` is the container itself; use the Compose service name.
- **Dashboard empty right after startup**: heartbeats and registry fetch are asynchronous; wait a few seconds.
- **Port conflict**: 8761 is the default — make sure nothing else uses it.
