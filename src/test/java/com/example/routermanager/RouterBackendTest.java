package com.example.routermanager;

import com.example.routermanager.monitor.*;
import com.example.routermanager.simulator.RouterSimulator;
import com.example.routermanager.usage.UsageQueryService;
import com.example.routermanager.usage.UsageRollupService;
import com.example.routermanager.usage.UsageDailyRepository;
import com.example.routermanager.usage.UsageHourlyRepository;
import com.example.routermanager.usage.UsageProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import com.example.routermanager.router.RouterProperties;
import com.example.routermanager.router.ZteWebClient;
import com.example.routermanager.router.RouterClientState;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"router.poll-enabled=false", "demo.traffic-interval=99999999"})
@ActiveProfiles("demo")
@Testcontainers
class RouterBackendTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired RouterPoller poller;
    @Autowired RouterSimulator simulator;
    @Autowired DeviceRepository devices;
    @Autowired DeviceEventRepository events;
    @Autowired CounterSampleRepository samples;
    @Autowired UsageQueryService usage;
    @Autowired UsageRollupService rollups;
    @Autowired RouterStatusRepository statuses;
    @Autowired PlatformTransactionManager tx;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired UsageDailyRepository daily;
    @Autowired UsageHourlyRepository hourly;
    @Autowired SampleRetention retention;
    @Autowired UsageProperties usageProperties;

    @Test
    void pollRecordsDevicesOnlyOnceAndUsesTwoMissingPollsForOffline() {
        String mac = "02:00:5e:00:00:ee";
        simulator.state().addWifiDevice(mac, "192.168.1.88", "test-phone", "SSID1", -55);
        poller.poll();
        assertThat(devices.findById(mac)).isPresent();
        assertThat(events.countByMacAndType(mac, DeviceEventType.NEW_DEVICE)).isEqualTo(1);
        poller.poll();
        assertThat(events.countByMacAndType(mac, DeviceEventType.NEW_DEVICE)).isEqualTo(1);
        simulator.state().setDeviceIp(mac, "192.168.1.89");
        poller.poll();
        assertThat(events.countByMacAndType(mac, DeviceEventType.IP_CHANGED)).isEqualTo(1);
        simulator.state().removeDevice(mac);
        poller.poll();
        assertThat(devices.findById(mac).orElseThrow().isOnline()).isTrue();
        poller.poll();
        assertThat(devices.findById(mac).orElseThrow().isOnline()).isFalse();
        assertThat(events.countByMacAndType(mac, DeviceEventType.OFFLINE)).isEqualTo(1);
    }

    @Test
    void rebootCannotCreateNegativeOrEnormousUsage() {
        poller.poll();
        simulator.state().reboot();
        simulator.state().advanceSsidBytes("SSID1", 12345, 6789);
        poller.poll();
        Instant now = Instant.now();
        var result = usage.summary(now.minusSeconds(3600), now.plusSeconds(60));
        assertThat(result.rxBytes()).isBetween(0L, 1_000_000_000L);
        assertThat(result.txBytes()).isBetween(0L, 1_000_000_000L);
    }

    @Test
    void rejectedCredentialsDoNotHammerSimulatorAndResetAllowsRecovery() {
        RouterProperties settings = new RouterProperties();
        settings.setUrl(simulator.baseUrl());
        settings.setUsername("demo");
        settings.setPassword("incorrect");
        ZteWebClient client = new ZteWebClient(settings, clock);
        RouterPoller isolated = new RouterPoller(client, settings, devices, events, samples,
                statuses, rollups, clock, tx, jdbc);
        int before = simulator.loginAttempts();
        isolated.poll();
        assertThat(client.state()).isIn(RouterClientState.AUTH_FAILED, RouterClientState.LOCKED);
        int after = simulator.loginAttempts();
        assertThat(after).isEqualTo(before + 1);
        for (int i = 0; i < 5; i++) isolated.poll();
        assertThat(simulator.loginAttempts()).isEqualTo(after);
        settings.setPassword("demo-password");
        client.resetAuth();
        isolated.poll();
        assertThat(client.state()).isEqualTo(RouterClientState.OK);
        assertThat(simulator.loginAttempts()).isEqualTo(after + 1);
        client.close();
    }

    @Test
    void cairoMidnightSplitsDailyBucketsAndRerunIsIdempotent() {
        String id = "CAIRO_TEST";
        Instant before = Instant.parse("2025-01-01T21:58:00Z");
        samples.save(CounterSampleEntity.of(before, CounterSourceType.SSID, id, 100, 200, 100L));
        samples.save(CounterSampleEntity.of(before.plusSeconds(60), CounterSourceType.SSID, id, 110, 220, 160L));
        samples.save(CounterSampleEntity.of(before.plusSeconds(180), CounterSourceType.SSID, id, 180, 240, 280L));
        rollups.rollUp(before, before.plusSeconds(240));
        var jan1 = daily.findWindow(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-01"))
                .stream().filter(row -> row.getSourceId().equals(id)).findFirst().orElseThrow();
        var jan2 = daily.findWindow(LocalDate.parse("2025-01-02"), LocalDate.parse("2025-01-02"))
                .stream().filter(row -> row.getSourceId().equals(id)).findFirst().orElseThrow();
        assertThat(jan1.getRxBytes()).isEqualTo(10);
        assertThat(jan2.getRxBytes()).isEqualTo(70);
        rollups.rollUp(before, before.plusSeconds(240));
        assertThat(daily.findWindow(LocalDate.parse("2025-01-02"), LocalDate.parse("2025-01-02"))
                .stream().filter(row -> row.getSourceId().equals(id)).findFirst().orElseThrow().getRxBytes()).isEqualTo(70);
    }

    @Test
    void retentionKeepsRollupsButRemovesOnlyOldRawReadings() {
        // PostgreSQL keeps microseconds; a Linux clock has nanoseconds, so truncate before comparing.
        Instant old = clock.instant().minusSeconds(20 * 86400L).truncatedTo(ChronoUnit.MICROS);
        Instant recent = clock.instant().minusSeconds(86400L).truncatedTo(ChronoUnit.MICROS);
        samples.save(CounterSampleEntity.of(old, CounterSourceType.SSID, "RETENTION_TEST", 100, 100, 1L));
        samples.save(CounterSampleEntity.of(recent, CounterSourceType.SSID, "RETENTION_TEST", 200, 200, 2L));
        rollups.rollUp(old, old.plusSeconds(1));
        long buckets = daily.count();
        retention.scheduledRetention();
        assertThat(samples.findBySourceTypeAndSourceIdOrderByTakenAtAsc(CounterSourceType.SSID, "RETENTION_TEST"))
                .hasSize(1).first().extracting(CounterSampleEntity::getTakenAt).isEqualTo(recent);
        assertThat(daily.count()).isEqualTo(buckets);
    }

    @Test
    void storesSsidAndLanPortReadings() {
        poller.poll();
        assertThat(samples.count()).isGreaterThan(0);
        assertThat(samples.findBySourceTypeAndSourceIdOrderByTakenAtAsc(CounterSourceType.SSID, "SSID1")).isNotEmpty();
        assertThat(samples.findBySourceTypeAndSourceIdOrderByTakenAtAsc(CounterSourceType.LAN_PORT, "LAN2")).isNotEmpty();
    }
}
