package com.ticketflow.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts the gateway on a random port and a tiny stub HTTP server standing in for
 * customer-service, so the routing can be tested without running Eureka or the real service.
 * <p>
 * Eureka is disabled and the in-memory SimpleDiscoveryClient declares the stub as the
 * only instance of "customer-service", so the {@code lb://} URI resolves without a registry.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
class GatewayRoutingTest {

    private static final AtomicReference<String> lastBackendPath = new AtomicReference<>();
    private static final HttpServer backend = startBackend();

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                lastBackendPath.set(exchange.getRequestURI().getPath());
                byte[] body = "{\"id\":1,\"firstName\":\"Sara\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.customer-service[0].uri",
                () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void forwardsCustomerRequestsToTheBackend() throws Exception {
        HttpResponse<String> response = get("/api/customers/1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Sara");
        assertThat(lastBackendPath.get()).isEqualTo("/api/customers/1");
    }

    @Test
    void addsTheGatewayResponseHeader() throws Exception {
        HttpResponse<String> response = get("/api/customers");

        assertThat(response.headers().firstValue("X-Served-By")).contains("api-gateway");
    }

    @Test
    void unknownPathsAreNotRouted() throws Exception {
        assertThat(get("/api/unknown").statusCode()).isEqualTo(404);
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }
}
