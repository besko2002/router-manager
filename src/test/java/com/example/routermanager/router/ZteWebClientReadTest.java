package com.example.routermanager.router;

import com.example.routermanager.simulator.RouterSimulator;
import com.example.routermanager.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client's behaviour against the simulator: the happy path and session handling. */
class ZteWebClientReadTest {

    private static final String USER = "admin";
    private static final String PASSWORD = "correct-horse";

    private RouterSimulator simulator;
    private MutableClock clock;
    private ZteWebClient client;

    @BeforeEach
    void start() {
        simulator = new RouterSimulator(USER, PASSWORD).start();
        clock = MutableClock.at("2025-01-01T10:00:00Z");
        client = new ZteWebClient(properties(simulator.baseUrl(), PASSWORD), clock);
    }

    private static RouterProperties properties(String url, String password) {
        RouterProperties properties = new RouterProperties();
        properties.setUrl(url);
        properties.setUsername(USER);
        properties.setPassword(password);
        properties.setInsecureTls(false);
        properties.setRequestTimeout(Duration.ofSeconds(5));
        return properties;
    }

    @AfterEach
    void stop() {
        client.close();
        simulator.close();
    }

    @Test
    @DisplayName("one read logs in and returns a complete snapshot")
    void readsEverything() {
        RouterSnapshot snapshot = client.read();

        assertThat(client.state()).isEqualTo(RouterClientState.OK);
        assertThat(client.loginAttempts()).isEqualTo(1);
        assertThat(snapshot.takenAt()).isEqualTo(clock.instant());

        assertThat(snapshot.routerInfo().model()).isEqualTo("H188A");
        assertThat(snapshot.routerInfo().firmware()).isEqualTo("V2.1.3P2_TE");
        assertThat(snapshot.routerInfo().hardware()).isEqualTo("V2.1.1");
        assertThat(snapshot.routerInfo().wanStatus()).isEqualTo("Connected");
        assertThat(snapshot.routerInfo().wanType()).isEqualTo("pppoe");
        assertThat(snapshot.routerInfo().uptimeSeconds()).isEqualTo(331_037L);

        assertThat(snapshot.devices()).hasSize(6);
        assertThat(snapshot.devicesOfKind(DeviceKind.WIFI)).hasSize(5);
        assertThat(snapshot.devicesOfKind(DeviceKind.WIRED)).hasSize(1);
        assertThat(snapshot.ssidCounters()).hasSize(8);
        assertThat(snapshot.portCounters()).extracting(PortCounter::port)
                .containsExactly("LAN1", "LAN2", "LAN3", "LAN4");
        assertThat(snapshot.dslRates().downCurrentKbps()).isEqualTo(40959);
    }

    @Test
    @DisplayName("the snapshot mirrors the fixture counters exactly")
    void snapshotCarriesFixtureCounters() {
        RouterSnapshot snapshot = client.read();

        SsidCounter ssid1 = snapshot.ssidCounters().get(0);
        assertThat(ssid1.alias()).isEqualTo("SSID1");
        assertThat(ssid1.rxBytes()).isEqualTo(1_503_556_195L);
        assertThat(ssid1.txBytes()).isEqualTo(3_342_582_966L);

        PortCounter lan2 = snapshot.portCounters().get(1);
        assertThat(lan2.rxBytes()).isEqualTo(26_711_705L);
        assertThat(lan2.txBytes()).isEqualTo(38_064_729L);
    }

    @Test
    @DisplayName("every data endpoint is preceded by opening its page")
    void opensPagesBeforeReadingData() {
        simulator.clearRequestLog();

        client.read();

        List<String> log = simulator.requestLog();
        assertThat(log).isNotEmpty();
        String lastPage = null;
        for (String entry : log) {
            if (entry.contains("_type=menuView")) {
                lastPage = entry.substring(entry.indexOf("_tag=") + 5);
            } else if (entry.contains("_type=menuData")) {
                assertThat(lastPage)
                        .as("a menuData call must follow a menuView: " + entry)
                        .isNotNull();
            }
        }
        assertThat(log).anyMatch(entry -> entry.contains("_tag=localNetStatus"));
        assertThat(log).anyMatch(entry -> entry.contains("_tag=dslWanStatus"));
        assertThat(log).anyMatch(entry -> entry.contains("_tag=arpTable"));
        assertThat(log).anyMatch(entry -> entry.contains("_tag=macTable"));
        assertThat(log).anyMatch(entry -> entry.contains("_tag=statusMgr"));
    }

    @Test
    @DisplayName("a data endpoint called before its page answers SessionTimeout (the rule we obey)")
    void simulatorEnforcesPageOrdering() {
        client.read();
        simulator.closeAllPages();

        // Reading again re-opens the pages, so this must still succeed.
        assertThat(client.read().devices()).hasSize(6);
    }

    @Test
    @DisplayName("many polls reuse ONE session: no extra login")
    void reusesTheSession() {
        client.read();
        client.read();
        client.read();

        assertThat(client.loginAttempts()).isEqualTo(1);
        assertThat(simulator.loginAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("an expired session triggers exactly one re-login inside the same poll")
    void recoversFromSessionTimeout() {
        client.read();
        simulator.expireSessions();

        RouterSnapshot snapshot = client.read();

        assertThat(snapshot.devices()).hasSize(6);
        assertThat(client.loginAttempts()).isEqualTo(2);
        assertThat(client.state()).isEqualTo(RouterClientState.OK);
    }

    @Test
    @DisplayName("an idle session that the router dropped is recovered the same way")
    void recoversFromIdleExpiry() {
        // The simulator shares the test clock, so "idle for six minutes" is exact, not a sleep.
        simulator.setClock(clock);
        simulator.setIdleTimeout(Duration.ofMinutes(5));
        client.read();

        clock.advance(Duration.ofMinutes(6));

        assertThat(client.read().devices()).hasSize(6);
        assertThat(client.loginAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a session that is dead twice in a row fails the poll instead of looping on logins")
    void doesNotLoopOnPersistentSessionTimeout() {
        // Idle timeout zero: every session is already stale by the time the next request arrives.
        RouterSimulator broken = new RouterSimulator(USER, PASSWORD).start();
        broken.setIdleTimeout(Duration.ZERO);
        ZteWebClient brokenClient =
                new ZteWebClient(properties(broken.baseUrl(), PASSWORD), clock);
        try {
            assertThatThrownBy(brokenClient::read).isInstanceOf(RouterSessionTimeoutException.class);
            // Exactly two login attempts: the first one plus the single allowed retry.
            assertThat(brokenClient.loginAttempts()).isEqualTo(2);
        } finally {
            brokenClient.close();
            broken.close();
        }
    }

    @Test
    @DisplayName("devices added and removed in the simulator show up in the next snapshot")
    void seesDeviceChanges() {
        assertThat(client.read().devices()).hasSize(6);

        simulator.state().addWifiDevice("02:00:5E:00:00:AB", "192.168.1.50", "tablet", "SSID1", -55);
        RouterSnapshot grown = client.read();
        assertThat(grown.devices()).hasSize(7);
        RouterDevice added = grown.devices().stream()
                .filter(device -> device.mac().equals("02:00:5e:00:00:ab")).findFirst().orElseThrow();
        assertThat(added.name()).isEqualTo("tablet");
        assertThat(added.ip()).isEqualTo("192.168.1.50");
        assertThat(added.ssid()).isEqualTo("SSID1");
        assertThat(added.rssi()).isEqualTo(-55);

        simulator.state().removeDevice("02:00:5e:00:00:ab");
        assertThat(client.read().devices()).hasSize(6);
    }

    @Test
    @DisplayName("advancing the simulator's counters is visible in the snapshot")
    void seesCounterChanges() {
        long before = client.read().ssidCounters().get(0).rxBytes();

        simulator.state().advanceSsidBytes("SSID1", 1_000, 2_000);
        simulator.state().advancePortBytes("LAN2", 500, 600);

        RouterSnapshot after = client.read();
        assertThat(after.ssidCounters().get(0).rxBytes()).isEqualTo(before + 1_000);
        assertThat(after.portCounters().get(1).rxBytes()).isEqualTo(26_711_705L + 500);
    }

    @Test
    @DisplayName("a reboot shows zeroed counters and uptime 0")
    void seesReboot() {
        client.read();
        simulator.state().reboot();

        RouterSnapshot after = client.read();

        assertThat(after.routerInfo().uptimeSeconds()).isZero();
        assertThat(after.ssidCounters()).allSatisfy(ssid -> assertThat(ssid.rxBytes()).isZero());
        assertThat(after.portCounters()).allSatisfy(port -> assertThat(port.txBytes()).isZero());
    }

    @Test
    @DisplayName("close() logs out and the next read logs in again")
    void logsOut() {
        client.read();
        assertThat(simulator.sessionCount()).isEqualTo(1);

        client.close();

        assertThat(simulator.sessionCount()).isZero();
        assertThat(client.read().devices()).hasSize(6);
        assertThat(client.loginAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("close() on a client that never logged in does nothing")
    void closeIsSafeWithoutSession() {
        client.close();

        assertThat(simulator.loginAttempts()).isZero();
    }

    @Test
    @DisplayName("the WAN uptime follows the simulator, which is how reboots are detected later")
    void uptimeFollowsSimulator() {
        assertThat(client.read().routerInfo().uptimeSeconds()).isEqualTo(331_037L);

        simulator.state().advanceUptime(60);

        assertThat(client.read().routerInfo().uptimeSeconds()).isEqualTo(331_097L);
    }
}
