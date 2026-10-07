package com.example.routermanager;

import com.example.routermanager.api.AppUserRepository;
import com.example.routermanager.monitor.RouterPoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@SpringBootTest(properties = {"router.poll-enabled=false", "demo.traffic-interval=99999999"})
@AutoConfigureMockMvc
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@ActiveProfiles("demo")
@Testcontainers
class Phase3ApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MockMvc mvc;
    @Autowired RouterPoller poller;
    @Autowired AppUserRepository users;
    private String token;
    private final String mac = "02:00:5e:00:00:02";

    @BeforeEach void prepare() throws Exception {
        poller.poll();
        if (users.count() == 0) mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"Owner\",\"password\":\"a-long-test-password\"}"))
                .andExpect(status().isCreated());
        token = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"OWNER\",\"password\":\"a-long-test-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    @Test void sixthFailedLoginIsRateLimitedWithRetryAfter() throws Exception {
        for (int i = 0; i < 5; i++) mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"rate-limit-owner\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"rate-limit-owner\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test void protectedApiRequiresToken() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
    @Test void healthIsPublic() throws Exception { mvc.perform(get("/actuator/health")).andExpect(status().isOk()); }
    @Test void docsArePublic() throws Exception { mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()); }
    @Test void setupIsUnavailableAfterOwnerExists() throws Exception {
        mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"other\",\"password\":\"a-long-test-password\"}"))
                .andExpect(status().isConflict());
    }
    @Test void meReturnsOnlyUsername() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("owner"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @Test void tamperedTokenIsRejected() throws Exception {
        mvc.perform(get("/api/devices").header("Authorization", "Bearer " + token + "x"))
                .andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @ValueSource(strings = {"bad", "missing"})
    void wrongCredentialsGetGenericError(String password) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"owner\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid username or password"));
    }
    @Test void aliasAndTrustRoundTrip() throws Exception {
        mvc.perform(put("/api/devices/{mac}/alias", mac).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"  🐈 <script>  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("🐈 <script>"));
        mvc.perform(put("/api/devices/{mac}/trusted", mac).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"trusted\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trusted").value(true));
        mvc.perform(get("/api/devices?trusted=true").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].alias").value("🐈 <script>"));
        mvc.perform(delete("/api/devices/{mac}/alias", mac).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/devices/{mac}/trusted", mac).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"trusted\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trusted").value(false));
    }
    @ParameterizedTest @ValueSource(strings = {"", "   ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bad\\nname"})
    void invalidAliasIsRejected(String alias) throws Exception {
        mvc.perform(put("/api/devices/{mac}/alias", mac).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"" + alias + "\"}"))
                .andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings = {
            "tablet", "Family screen", "Café", "猫", "🍉", "🧑🏽‍💻", "عائلتي", "принтер", "<script>alert(1)</script>",
            "a&b", "Joe's phone", "𝄞", "émoji📱", "TV 📺", "test <b>bold</b>"})
    void unicodeAndMarkupAliasesRemainJSONStrings(String alias) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("alias", alias));
        mvc.perform(put("/api/devices/{mac}/alias", mac).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alias").value(alias));
    }
    @ParameterizedTest @ValueSource(strings = {"SSID", "LAN_PORT"})
    void labelsCanBeStoredForBothSourceTypes(String type) throws Exception {
        mvc.perform(put("/api/labels/{type}/test-source", type).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Home 🏠\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("Home 🏠"));
        mvc.perform(delete("/api/labels/{type}/test-source", type).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "  ", "\t", "\n", "\u0001", "\u007f",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void labelsRejectBlankControlsAndTooLongText(String label) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("label", label));
        mvc.perform(put("/api/labels/SSID/test-source").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings = {"malformed", "aa:bb:cc:dd:ee", "aa:bb:cc:dd:ee:gg", "00"})
    void aliasRejectsMalformedMac(String invalidMac) throws Exception {
        mvc.perform(put("/api/devices/{mac}/alias", invalidMac).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"test\"}"))
                .andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings = {"bad", "LAN", "device", "WIFI", "ssid1"})
    void labelsRejectUnknownSourceTypes(String type) throws Exception {
        mvc.perform(put("/api/labels/{type}/test", type).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"test\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void unknownDeviceIs404() throws Exception {
        mvc.perform(put("/api/devices/02:00:5e:ff:ff:ff/alias").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"test\"}"))
                .andExpect(status().isNotFound());
    }
    @Test void labelIsInSummaryAndTimeseriesAndSettings() throws Exception {
        mvc.perform(put("/api/labels/SSID/SSID1").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Guest 🧩\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/usage/summary").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.bySsid[0].label").value("Guest 🧩"));
        mvc.perform(get("/api/usage/timeseries?groupBy=ssid").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.series[0].label").value("Guest 🧩"));
        mvc.perform(get("/api/router/wifi").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.items[0].label").value("Guest 🧩"));
        mvc.perform(delete("/api/labels/SSID/SSID1").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }
    @Test void loginDoesNotLogOrReturnPassword(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\",\"password\":\"a-long-test-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.password").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("a-long-test-password");
    }
    @Test void passwordCanBeChangedAndRestoredWithoutLeakingHash() throws Exception {
        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"a-long-test-password\",\"newPassword\":\"new-test-password-123\"}"))
                .andExpect(status().isNoContent());
        try {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"owner\",\"password\":\"new-test-password-123\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        } finally {
            mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"new-test-password-123\",\"newPassword\":\"a-long-test-password\"}"))
                    .andExpect(status().isNoContent());
        }
    }
    @Test void passwordChangeRejectsWrongCurrentPassword() throws Exception {
        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"wrong\",\"newPassword\":\"new-long-password\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
    }
    @Test void passwordChangeRejectsShortPassword() throws Exception {
        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"a-long-test-password\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void missingTrustFlagIsBadRequest() throws Exception {
        mvc.perform(put("/api/devices/{mac}/trusted", mac).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
    @Test void missingAliasIsBadRequest() throws Exception {
        mvc.perform(put("/api/devices/{mac}/alias", mac).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
    @Test void devicesRetainBackwardCompatibleFields() throws Exception {
        mvc.perform(get("/api/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].mac").exists())
                .andExpect(jsonPath("$[0].name").hasJsonPath()).andExpect(jsonPath("$[0].online").isBoolean())
                .andExpect(jsonPath("$[0].trusted").isBoolean()).andExpect(jsonPath("$[0].displayName").exists());
    }
    @Test void wifiExposesStoredSecurityAndConnectedCount() throws Exception {
        mvc.perform(get("/api/router/wifi").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].securityMode").exists())
                .andExpect(jsonPath("$.items[0].connectedDeviceCount").isNumber());
    }
    @Test void portsAreReadOnly() throws Exception {
        mvc.perform(get("/api/router/ports").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.items[0].status").exists());
    }
}
