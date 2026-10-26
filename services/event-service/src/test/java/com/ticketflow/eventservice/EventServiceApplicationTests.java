package com.ticketflow.eventservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.config.import=optional:configserver:http://localhost:8888",
    "spring.cloud.config.enabled=false",
    "eureka.client.enabled=false",
    "server.port=0",
    "management.endpoints.web.exposure.include=health,info"
})
class EventServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
