package com.example.routermanager;

import com.example.routermanager.api.AppUserRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"router.poll-enabled=false", "demo.traffic-interval=99999999",
        "app.admin.username=", "app.admin.password="})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Testcontainers
class OwnerSetupStatusTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;

    @Test void publicStatusTracksOneTimeSetupWithoutLeakingUserData() throws Exception {
        assertEquals(0, users.count());
        mvc.perform(get("/api/auth/setup-status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\",\"password\":\"long-test-password\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/auth/setup-status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupRequired").value(false))
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"second\",\"password\":\"long-test-password\"}"))
                .andExpect(status().isConflict());
    }

    @Test void setupStatusAndSetupSharePerIpLimit() throws Exception {
        for (int i = 0; i < 30; i++) mvc.perform(get("/api/auth/setup-status").with(request -> {
            request.setRemoteAddr("198.51.100.42"); return request;
        })).andExpect(status().isOk());
        mvc.perform(post("/api/auth/setup").with(request -> {
            request.setRemoteAddr("198.51.100.42"); return request;
        }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\",\"password\":\"long-test-password\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
}
