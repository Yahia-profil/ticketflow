package com.ticketflow.customerservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.config.import=optional:configserver:http://localhost:8888",
    "spring.cloud.config.enabled=false",
    "eureka.client.enabled=false",
    "server.port=0",
    "ticketflow.message=test-message",
    "management.endpoints.web.exposure.include=health,info"
})
class CustomerServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
