package com.example.routermanager.simulator;

import com.example.routermanager.router.AjaxDocument;
import com.example.routermanager.router.AjaxXml;
import com.example.routermanager.router.DslRates;
import com.example.routermanager.router.PortCounter;
import com.example.routermanager.router.RouterDevice;
import com.example.routermanager.router.RouterHttpTransport;
import com.example.routermanager.router.RouterInfo;
import com.example.routermanager.router.SsidCounter;
import com.example.routermanager.router.ZteEndpoints;
import com.example.routermanager.router.ZteResponseParser;
import com.example.routermanager.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The simulator must be a faithful stand-in, otherwise the client tests prove nothing. These tests
 * drive it at the raw HTTP level and compare what it serves with the recorded fixtures.
 */
class RouterSimulatorTest {

    private static final String USER = "admin";
    private static final String PASSWORD = "correct-horse";

    private RouterSimulator simulator;
    private RouterHttpTransport http;

    @BeforeEach
    void start() {
        simulator = new RouterSimulator(USER, PASSWORD).start();
        http = new RouterHttpTransport(simulator.baseUrl(), false, 5000);
    }

    @AfterEach
    void stop() {
        simulator.close();
    }

    // ------------------------------------------------------------------ protocol

    @Test
    @DisplayName("GET / hands out the session cookie and the logout token")
    void rootIssuesCookieAndToken() {
        RouterHttpTransport.Response response = http.get(ZteEndpoints.ROOT);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("_sessionTmpToken");
        assertThat(http.cookies()).containsKey("SID_HTTPS_");
    }

    @Test
    @DisplayName("the login state call reports an unlocked router with a sess_token")
    void loginStateIsUnlockedInitially() {
        http.get(ZteEndpoints.ROOT);

        String body = http.get(ZteEndpoints.LOGIN_STATE).body();

        assertThat(body).contains("\"lockingTime\":0").contains("sess_token");
    }

    @Test
    @DisplayName("login_token answers the bare-number XML shape")
    void loginTokenShape() {
        http.get(ZteEndpoints.ROOT);

        String body = http.get(ZteEndpoints.LOGIN_TOKEN).body();

        assertThat(AjaxXml.parse(body).rootText()).matches("\\d+");
    }

    @Test
    @DisplayName("data before logging in answers SessionTimeout")
    void dataWithoutLoginIsSessionTimeout() {
        http.get(ZteEndpoints.ROOT);

        AjaxDocument document = AjaxXml.parse(
                http.get(ZteEndpoints.menuData(ZteEndpoints.WLAN_STATUS, null, 1)).body());

        assertThat(document.isSessionTimeout()).isTrue();
    }

    @Test
    @DisplayName("data without opening its page answers SessionTimeout, even when logged in")
    void dataWithoutPageIsSessionTimeout() {
        login();

        AjaxDocument document = AjaxXml.parse(
                http.get(ZteEndpoints.menuData(ZteEndpoints.WLAN_STATUS, null, 1)).body());

        assertThat(document.isSessionTimeout()).isTrue();
    }

    @Test
    @DisplayName("opening the page first makes the same call succeed")
    void dataAfterPageSucceeds() {
        login();

        http.get(ZteEndpoints.menuView(ZteEndpoints.PAGE_LOCAL_NET));
        AjaxDocument document = AjaxXml.parse(
                http.get(ZteEndpoints.menuData(ZteEndpoints.WLAN_STATUS, null, 1)).body());

        assertThat(document.isSessionTimeout()).isFalse();
        assertThat(document.errorStr()).isEqualTo("SUCC");
    }

    @Test
    @DisplayName("the page of one group does not unlock another group's endpoint")
    void pagesAreNotInterchangeable() {
        login();

        http.get(ZteEndpoints.menuView(ZteEndpoints.PAGE_LOCAL_NET));
        AjaxDocument document = AjaxXml.parse(
                http.get(ZteEndpoints.menuData(ZteEndpoints.STATUS_MGR, null, 1)).body());

        assertThat(document.isSessionTimeout()).isTrue();
    }

    @Test
    @DisplayName("an unknown data endpoint answers SessionTimeout rather than inventing data")
    void unknownEndpoint() {
        login();
        http.get(ZteEndpoints.menuView(ZteEndpoints.PAGE_LOCAL_NET));

        AjaxDocument document = AjaxXml.parse(
                http.get(ZteEndpoints.menuData("no_such_endpoint.lua", null, 1)).body());

        assertThat(document.isSessionTimeout()).isTrue();
    }

    @Test
    @DisplayName("a wrong password is refused with a non-zero lockingTime and a promptMsg")
    void wrongPasswordReply() {
        String body = attemptLogin("wrong");

        assertThat(body).contains("\"lockingTime\":60").contains("You have login failed");
        assertThat(simulator.failedLogins()).isEqualTo(1);
        assertThat(simulator.locked()).isFalse();
    }

    @Test
    @DisplayName("three wrong passwords lock the router, and the state call says so")
    void locksAfterThreeFailures() {
        attemptLogin("wrong");
        attemptLogin("wrong");
        assertThat(simulator.locked()).isFalse();

        attemptLogin("wrong");

        assertThat(simulator.locked()).isTrue();
        // The remaining lock time counts down from 60, so only "non-zero" is assertable here.
        assertThat(http.get(ZteEndpoints.LOGIN_STATE).body())
                .containsPattern("\"lockingTime\":[1-9][0-9]*")
                .contains("You have login failed for 3 times");
    }

    @Test
    @DisplayName("while locked, even the correct password is refused")
    void correctPasswordRefusedWhileLocked() {
        simulator.lockNow();

        assertThat(attemptLogin(PASSWORD)).contains("You have login failed");
        assertThat(simulator.sessionCount()).isZero();
    }

    @Test
    @DisplayName("unlock() clears the lock and the failure counter")
    void unlockClearsState() {
        attemptLogin("wrong");
        simulator.lockNow();

        simulator.unlock();

        assertThat(simulator.locked()).isFalse();
        assertThat(simulator.failedLogins()).isZero();
        login();
        assertThat(simulator.sessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a wrong _sessionTOKEN is refused even with the right password")
    void wrongSessionTokenIsRefused() {
        http.get(ZteEndpoints.ROOT);
        http.get(ZteEndpoints.LOGIN_STATE);
        String token = AjaxXml.parse(http.get(ZteEndpoints.LOGIN_TOKEN).body()).rootText();

        Map<String, String> form = new LinkedHashMap<>();
        form.put("Username", USER);
        form.put("Password", RouterSimulator.sha256Hex(PASSWORD + token));
        form.put("action", "login");
        form.put("_sessionTOKEN", "not-the-token");
        String body = http.postForm(ZteEndpoints.LOGIN_STATE, form).body();

        assertThat(body).contains("You have login failed");
        assertThat(simulator.sessionCount()).isZero();
    }

    @Test
    @DisplayName("logout ends the session; the next data call is a SessionTimeout")
    void logoutEndsTheSession() {
        login();
        http.get(ZteEndpoints.menuView(ZteEndpoints.PAGE_LOCAL_NET));
        assertThat(simulator.sessionCount()).isEqualTo(1);

        http.postForm(ZteEndpoints.LOGOUT, Map.of("IF_LogOff", "1", "_sessionTOKEN", "x"));

        assertThat(simulator.sessionCount()).isZero();
        assertThat(AjaxXml.parse(http.get(ZteEndpoints.menuData(ZteEndpoints.WLAN_STATUS, null, 1)).body())
                .isSessionTimeout()).isTrue();
    }

    @Test
    @DisplayName("expireSessions() simulates the router forgetting us")
    void expireSessions() {
        login();
        http.get(ZteEndpoints.menuView(ZteEndpoints.PAGE_LOCAL_NET));

        simulator.expireSessions();

        assertThat(AjaxXml.parse(http.get(ZteEndpoints.menuData(ZteEndpoints.WLAN_STATUS, null, 1)).body())
                .isSessionTimeout()).isTrue();
    }

    @Test
    @DisplayName("the request log records what was asked, in order")
    void requestLog() {
        simulator.clearRequestLog();
        login();

        assertThat(simulator.requestLog()).isNotEmpty();
        assertThat(simulator.requestLog().get(0)).contains("GET");
        assertThat(simulator.requestLog()).anyMatch(entry -> entry.contains("login_token"));
    }

    // ------------------------------------------------------------------ fidelity

    @Test
    @DisplayName("the served Wi-Fi clients parse to the same model as the fixture")
    void wifiDevicesMatchFixture() {
        List<RouterDevice> fromFixture =
                ZteResponseParser.parseWifiDevices(Fixtures.doc("accessdev_ssiddev_lua"));

        List<RouterDevice> served = ZteResponseParser.parseWifiDevices(served("accessdev_ssiddev_lua"));

        assertThat(served).hasSameSizeAs(fromFixture);
        assertThat(served).usingRecursiveFieldByFieldElementComparatorIgnoringFields()
                .containsExactlyElementsOf(fromFixture);
    }

    @Test
    @DisplayName("the served wired clients parse to the same model as the fixture")
    void wiredDevicesMatchFixture() {
        List<RouterDevice> fromFixture =
                ZteResponseParser.parseWiredDevices(Fixtures.doc("accessdev_landevs_lua"));

        assertThat(ZteResponseParser.parseWiredDevices(served("accessdev_landevs_lua")))
                .containsExactlyElementsOf(fromFixture);
    }

    @Test
    @DisplayName("the served SSID counters parse to the same model as the fixture")
    void ssidCountersMatchFixture() {
        List<SsidCounter> fromFixture = ZteResponseParser.parseSsidCounters(Fixtures.doc("wlan_status_lua"));

        assertThat(ZteResponseParser.parseSsidCounters(served("wlan_status_lua")))
                .containsExactlyElementsOf(fromFixture);
    }

    @Test
    @DisplayName("the served LAN port counters parse to the same model as the fixture")
    void portCountersMatchFixture() {
        List<PortCounter> fromFixture = ZteResponseParser.parsePortCounters(Fixtures.doc("eth_lanstatus_lua"));

        assertThat(ZteResponseParser.parsePortCounters(served("eth_lanstatus_lua")))
                .containsExactlyElementsOf(fromFixture);
    }

    @Test
    @DisplayName("the served DSL rates and device info match the fixtures")
    void dslAndInfoMatchFixture() {
        DslRates fromFixture = ZteResponseParser.parseDslRates(Fixtures.doc("dsl_interface_status_lua"));
        RouterInfo infoFromFixture = ZteResponseParser.parseDeviceInfo(Fixtures.doc("devmgr_statusmgr_lua"));

        assertThat(ZteResponseParser.parseDslRates(served("dsl_interface_status_lua")))
                .isEqualTo(fromFixture);
        assertThat(ZteResponseParser.parseDeviceInfo(served("devmgr_statusmgr_lua")))
                .isEqualTo(infoFromFixture);
    }

    @Test
    @DisplayName("the served WAN reply keeps the live connection distinguishable from the leftovers")
    void wanMatchesFixture() {
        RouterInfo served = ZteResponseParser.mergeWanInfo(RouterInfo.empty(), served("wan_internet_lua"));

        assertThat(served.wanStatus()).isEqualTo("Connected");
        assertThat(served.wanName()).isEqualTo("WAN0");
        assertThat(served.uptimeSeconds()).isEqualTo(331_037L);
        assertThat(served("wan_internet_lua").allInstances())
                .as("the unconfigured leftover is served too, like the real router does")
                .hasSize(2);
    }

    @Test
    @DisplayName("ARP and MAC tables are derived from the current device list")
    void tablesFollowDevices() {
        assertThat(ZteResponseParser.parseArpTable(served("arp_arptable_lua"))).hasSize(6);

        simulator.state().addWifiDevice("02:00:5e:00:00:cc", "192.168.1.60", "new", "SSID1", -60);

        assertThat(ZteResponseParser.parseArpTable(served("arp_arptable_lua")))
                .hasSize(7)
                .containsEntry("02:00:5e:00:00:cc", "192.168.1.60");
        assertThat(ZteResponseParser.parseMacTable(served("macinfo_mactable_lua")))
                .containsEntry("02:00:5e:00:00:cc", "SSID1");
    }

    // ------------------------------------------------------------------ state hooks

    @Test
    @DisplayName("counters advance and a reboot zeroes them together with the uptime")
    void countersAndReboot() {
        SimulatorState state = simulator.state();
        long before = state.ssidRxBytes("SSID1");

        state.advanceSsidBytes("SSID1", 1_234, 5_678);
        assertThat(state.ssidRxBytes("SSID1")).isEqualTo(before + 1_234);

        state.advancePortBytes("LAN2", 10, 20);
        state.advanceUptime(300);

        state.reboot();

        assertThat(state.ssidRxBytes("SSID1")).isZero();
        assertThat(state.portRxBytes("LAN2")).isZero();
        assertThat(state.uptimeSeconds()).isZero();
        assertThat(state.rebootCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("devices can be added, re-addressed and removed")
    void deviceMutation() {
        SimulatorState state = simulator.state();
        assertThat(state.deviceMacs()).hasSize(6);

        state.addWiredDevice("02:00:5e:00:00:dd", "192.168.1.70", "nas", "LAN3");
        assertThat(state.deviceMacs()).contains("02:00:5e:00:00:dd");

        state.setDeviceIp("02:00:5e:00:00:dd", "192.168.1.71");
        assertThat(ZteResponseParser.parseWiredDevices(served("accessdev_landevs_lua")))
                .anyMatch(device -> device.ip().equals("192.168.1.71"));

        assertThat(state.removeDevice("02:00:5e:00:00:dd")).isTrue();
        assertThat(state.removeDevice("02:00:5e:00:00:dd")).isFalse();
        assertThat(state.deviceMacs()).hasSize(6);
    }

    @Test
    @DisplayName("adding a device twice replaces it instead of duplicating the MAC")
    void addingTwiceReplaces() {
        SimulatorState state = simulator.state();

        state.addWifiDevice("02:00:5e:00:00:ee", "192.168.1.80", "a", "SSID1", -50);
        state.addWifiDevice("02:00:5E:00:00:EE", "192.168.1.81", "b", "SSID1", -51);

        assertThat(state.deviceMacs()).filteredOn("02:00:5e:00:00:ee"::equals).hasSize(1);
        assertThat(ZteResponseParser.parseWifiDevices(served("accessdev_ssiddev_lua")))
                .anyMatch(device -> "b".equals(device.name()) && "192.168.1.81".equals(device.ip()));
    }

    @Test
    @DisplayName("a port can be taken down and its counters set directly")
    void portMutation() {
        SimulatorState state = simulator.state();

        state.setPortStatus("LAN2", "NoLink", 10);
        state.setPortBytes("LAN2", 999, 888);
        state.setSsidBytes("SSID1", 7, 8);

        List<PortCounter> ports = ZteResponseParser.parsePortCounters(served("eth_lanstatus_lua"));
        assertThat(ports.get(1).status()).isEqualTo("NoLink");
        assertThat(ports.get(1).linkSpeedMbps()).isEqualTo(10);
        assertThat(ports.get(1).rxBytes()).isEqualTo(999);
        assertThat(ZteResponseParser.parseSsidCounters(served("wlan_status_lua")).get(0).rxBytes())
                .isEqualTo(7);
    }

    @Test
    @DisplayName("the state exposes the SSID and port names the fixtures contain")
    void exposesAliases() {
        assertThat(simulator.state().ssidAliases())
                .containsExactly("SSID1", "SSID2", "SSID3", "SSID4", "SSID5", "SSID6", "SSID7", "SSID8");
        assertThat(simulator.state().portAliases()).containsExactly("LAN1", "LAN2", "LAN3", "LAN4");
    }

    // ------------------------------------------------------------------ helpers

    private void login() {
        attemptLogin(PASSWORD);
    }

    private String attemptLogin(String password) {
        http.get(ZteEndpoints.ROOT);
        String state = http.get(ZteEndpoints.LOGIN_STATE).body();
        String sessToken = state.replaceAll("(?s).*\"sess_token\":\"([^\"]*)\".*", "$1");
        String token = AjaxXml.parse(http.get(ZteEndpoints.LOGIN_TOKEN).body()).rootText();

        Map<String, String> form = new LinkedHashMap<>();
        form.put("Username", USER);
        form.put("Password", RouterSimulator.sha256Hex(password + token));
        form.put("action", "login");
        form.put("_sessionTOKEN", sessToken);
        return http.postForm(ZteEndpoints.LOGIN_STATE, form).body();
    }

    /** Reads one endpoint the way the client does: open the page, then the data call. */
    private AjaxDocument served(String fixtureName) {
        if (simulator.sessionCount() == 0) {
            login();
        }
        String endpoint = fixtureName + ".lua";
        String page = switch (fixtureName) {
            case "dsl_interface_status_lua", "wan_internet_lua" -> ZteEndpoints.PAGE_DSL_WAN;
            case "arp_arptable_lua" -> ZteEndpoints.PAGE_ARP;
            case "macinfo_mactable_lua" -> ZteEndpoints.PAGE_MAC;
            case "devmgr_statusmgr_lua" -> ZteEndpoints.PAGE_STATUS_MGR;
            default -> ZteEndpoints.PAGE_LOCAL_NET;
        };
        http.get(ZteEndpoints.menuView(page));
        String extra = "wan_internet_lua".equals(fixtureName) ? ZteEndpoints.WAN_INTERNET_EXTRA : null;
        return AjaxXml.parse(http.get(ZteEndpoints.menuData(endpoint, extra, 1)).body());
    }
}
