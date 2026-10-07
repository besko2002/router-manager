package com.example.routermanager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"router.poll-enabled=false", "demo.traffic-interval=99999999",
        "app.admin.username=BootstrapOWNER", "app.admin.password=bootstrap-password-only-for-tests"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Testcontainers
class BootstrapAuthTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MockMvc mvc;

    @Test void startupCreatesLowercaseOwnerAndClosesSetup() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bootstrapowner\",\"password\":\"bootstrap-password-only-for-tests\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.username").value("bootstrapowner"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"second\",\"password\":\"another-long-password\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }
}
