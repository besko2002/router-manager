package com.example.routermanager;

import com.example.routermanager.monitor.RouterPoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"router.poll-enabled=false", "demo.traffic-interval=99999999"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Testcontainers
class RouterApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MockMvc mvc;
    @Autowired RouterPoller poller;
    @Autowired com.example.routermanager.api.AppUserRepository users;
    @Autowired com.example.routermanager.api.AppJwt jwt;
    @Autowired com.example.routermanager.api.AppAuthFilter authFilter;
    @Autowired org.springframework.web.context.WebApplicationContext context;

    @BeforeEach void poll() {
        poller.poll();
        var user = users.findByUsername("baseline-owner").orElseGet(() -> users.save(
                new com.example.routermanager.api.AppUser("baseline-owner", "unused-test-hash", java.time.Instant.now())));
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .defaultRequest(get("/").header("Authorization", "Bearer " + jwt.issue(user))).build();
    }

    @Test void statusHasRouterIdentity() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OK"))
                .andExpect(jsonPath("$.model").exists())
                .andExpect(jsonPath("$.lastOkAt").exists())
                .andExpect(jsonPath("$.onlineDevices").isNumber());
    }
    @Test void devicesHaveLinkIdentityButNoInventedByteCounters() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mac").exists())
                .andExpect(jsonPath("$[0].firstSeen").exists())
                .andExpect(jsonPath("$[0].online").isBoolean())
                .andExpect(jsonPath("$[0].rxBytes").doesNotExist());
    }
    @Test void onlineFilterReturnsOnlyOnline() throws Exception {
        mvc.perform(get("/api/devices?online=true")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].online").value(true));
    }
    @Test void offlineFilterIsEmptyInitially() throws Exception {
        mvc.perform(get("/api/devices?online=false")).andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
    @Test void invalidOnlineFilterIsBadRequest() throws Exception {
        mvc.perform(get("/api/devices?online=maybe")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
    @Test void malformedMacIsBadRequest() throws Exception {
        mvc.perform(get("/api/devices/not-a-mac/events")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed MAC address"));
    }
    @Test void unknownMacIsNotFound() throws Exception {
        mvc.perform(get("/api/devices/02:00:5e:ff:ff:ff/events")).andExpect(status().isNotFound());
    }
    @Test void knownMacEventsContainFirstSighting() throws Exception {
        String mac = "02:00:5e:00:00:02";
        mvc.perform(get("/api/devices/" + mac + "/events")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("NEW_DEVICE"));
    }
    @Test void summaryIsMarkedApproximateAndHasComponents() throws Exception {
        mvc.perform(get("/api/usage/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.approximate").value(true))
                .andExpect(jsonPath("$.notes").isArray())
                .andExpect(jsonPath("$.total.rxBytes").isNumber())
                .andExpect(jsonPath("$.bySsid[0].id").exists())
                .andExpect(jsonPath("$.byPort[0].id").exists());
    }
    @ParameterizedTest @CsvSource({"hour,total", "hour,ssid", "hour,port", "day,total", "day,ssid", "day,port"})
    void timeseriesSupportsEachGrouping(String granularity, String groupBy) throws Exception {
        mvc.perform(get("/api/usage/timeseries").param("granularity", granularity).param("groupBy", groupBy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value(granularity))
                .andExpect(jsonPath("$.groupBy").value(groupBy))
                .andExpect(jsonPath("$.series[0].points[0].at").exists());
    }
    @ParameterizedTest @CsvSource({"minute,total", "hour,device", "bad,bad"})
    void invalidTimeseriesModesAreBadRequests(String granularity, String groupBy) throws Exception {
        mvc.perform(get("/api/usage/timeseries").param("granularity", granularity).param("groupBy", groupBy))
                .andExpect(status().isBadRequest());
    }
    @ParameterizedTest @CsvSource({"hour,2025-01-01,2025-01-17", "day,2024-01-01,2025-03-01"})
    void rangeCapsReturnBadRequest(String granularity, String from, String to) throws Exception {
        mvc.perform(get("/api/usage/timeseries").param("granularity", granularity)
                        .param("from", from).param("to", to))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }
    @Test void malformedDateReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/usage/summary?from=not-a-date")).andExpect(status().isBadRequest());
    }
    @Test void reversedDateReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/usage/summary?from=2025-01-02&to=2025-01-01")).andExpect(status().isBadRequest());
    }
    @Test void authResetReturnsNoContent() throws Exception {
        mvc.perform(post("/api/admin/router/reset-auth")).andExpect(status().isNoContent());
    }
    @ParameterizedTest @ValueSource(strings = {"abc", "02:00:5e:00:00:gg", "02:00:5e:00:00", "02005e00000", "::::::", "02:00:5e:00:00:01:22"})
    void malformedMacVariantsReturn400(String mac) throws Exception {
        mvc.perform(get("/api/devices/{mac}/events", mac)).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings = {"02005effffff", "02-00-5e-ff-ff-ff", "aa:bb:cc:dd:ee:ff", "AABBCCDDEEFF"})
    void validUnknownMacVariantsReturn404(String mac) throws Exception {
        mvc.perform(get("/api/devices/{mac}/events", mac)).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings = {"tomorrow", "2025-99-01", "2025-02-30", "2025/01/01", "2025-01-01T09:00:00", "2025-01-01Z"})
    void malformedDatesReturn400(String date) throws Exception {
        mvc.perform(get("/api/usage/summary").param("from", date)).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @CsvSource({"2025-01-01,2025-01-14", "2025-02-01,2025-02-14", "2025-03-01,2025-03-14", "2025-04-01,2025-04-14"})
    void fourteenDayHourlyWindowIsAccepted(String from, String to) throws Exception {
        mvc.perform(get("/api/usage/timeseries").param("from", from).param("to", to))
                .andExpect(status().isOk()).andExpect(jsonPath("$.series").isArray());
    }
    @ParameterizedTest @CsvSource({"2025-01-01,2025-01-16", "2025-02-01,2025-02-16", "2025-03-01,2025-03-16", "2025-04-01,2025-04-16"})
    void exceedingFourteenDayHourlyWindowReturns400(String from, String to) throws Exception {
        mvc.perform(get("/api/usage/timeseries").param("from", from).param("to", to))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }
    @ParameterizedTest @CsvSource({"2024-01-01,2025-02-05", "2023-01-01,2024-02-06", "2022-01-01,2023-02-06", "2021-01-01,2022-02-06"})
    void exceedingFourHundredDayWindowReturns400(String from, String to) throws Exception {
        mvc.perform(get("/api/usage/timeseries").param("granularity", "day")
                        .param("from", from).param("to", to)).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @CsvSource({"2025-01-01,2025-01-02", "2025-02-01,2025-02-02", "2025-03-01,2025-03-02", "2025-04-01,2025-04-02"})
    void dateOnlySummaryReturnsLocalDayBounds(String from, String to) throws Exception {
        mvc.perform(get("/api/usage/summary").param("from", from).param("to", to))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approximate").value(true))
                .andExpect(jsonPath("$.from").exists()).andExpect(jsonPath("$.to").exists());
    }
    @Test void swaggerDocsAreServed() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/status']").exists());
    }
}
