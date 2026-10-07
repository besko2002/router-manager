package com.example.routermanager.monitor;

import com.example.routermanager.router.*;
import com.example.routermanager.usage.UsageRollupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Persists complete successful reads; failed reads never mark devices offline. */
@Component
public class RouterPoller {
    private static final Logger log = LoggerFactory.getLogger(RouterPoller.class);
    private final RouterClient client;
    private final RouterProperties properties;
    private final DeviceRepository devices;
    private final DeviceEventRepository events;
    private final CounterSampleRepository samples;
    private final RouterStatusRepository statuses;
    private final UsageRollupService rollups;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public RouterPoller(RouterClient client, RouterProperties properties, DeviceRepository devices,
                        DeviceEventRepository events, CounterSampleRepository samples,
                        RouterStatusRepository statuses, UsageRollupService rollups, Clock clock,
                        PlatformTransactionManager transactionManager, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.client = client;
        this.properties = properties;
        this.devices = devices;
        this.events = events;
        this.samples = samples;
        this.statuses = statuses;
        this.rollups = rollups;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${router.poll-interval:60s}")
    public void scheduledPoll() {
        if (properties.isPollEnabled()) poll();
    }

    /** Safe to call directly in tests; no scheduler exception can escape. */
    public synchronized void poll() {
        if (client.state() == RouterClientState.AUTH_FAILED) {
            recordFailure();
            return;
        }
        try {
            RouterSnapshot snapshot = client.read();
            transaction.executeWithoutResult(ignored -> persist(snapshot));
        } catch (Exception ex) {
            log.warn("Router poll failed: {}", ex.getClass().getSimpleName());
            try {
                recordFailure();
            } catch (Exception storageFailure) {
                log.error("Could not save router poll failure", storageFailure);
            }
        }
    }

    @Transactional
    public void persist(RouterSnapshot snapshot) {
        Instant now = snapshot.takenAt() == null ? clock.instant() : snapshot.takenAt();
        RouterStatusEntity status = statuses.findById(1).orElseGet(RouterStatusEntity::new);
        Set<String> seen = new HashSet<>();
        for (RouterDevice source : snapshot.devices()) {
            if (source.mac() == null || source.mac().isBlank() || !seen.add(source.mac())) continue;
            DeviceEntity device = devices.findById(source.mac()).orElse(null);
            boolean firstSeen = device == null;
            if (firstSeen) {
                device = new DeviceEntity();
                device.setMac(source.mac());
                device.setFirstSeen(now);
            } else {
                if (!device.isOnline()) events.save(DeviceEventEntity.of(source.mac(), now, DeviceEventType.ONLINE, null));
                if (!Objects.equals(device.getLastIp(), source.ip()))
                    events.save(DeviceEventEntity.of(source.mac(), now, DeviceEventType.IP_CHANGED,
                            device.getLastIp() + " -> " + source.ip()));
            }
            device.setKind(source.kind());
            device.setName(source.name());
            device.setSsid(source.ssid());
            device.setPort(source.port());
            device.setLastIp(source.ip());
            device.setLastRssi(source.rssi());
            device.setLinkTxKbps(source.linkTxKbps());
            device.setLinkRxKbps(source.linkRxKbps());
            device.setLastSeen(now);
            device.setOnline(true);
            device.setMissedPolls(0);
            if (firstSeen) devices.saveAndFlush(device);
            else devices.save(device);
            if (firstSeen) events.save(DeviceEventEntity.of(source.mac(), now, DeviceEventType.NEW_DEVICE, null));
        }
        for (DeviceEntity device : devices.findByOnlineOrderByLastSeenDesc(true)) {
            if (!seen.contains(device.getMac())) {
                device.setMissedPolls(device.getMissedPolls() + 1);
                if (device.getMissedPolls() >= 2) {
                    device.setOnline(false);
                    events.save(DeviceEventEntity.of(device.getMac(), now, DeviceEventType.OFFLINE, null));
                }
                devices.save(device);
            }
        }
        Long uptime = snapshot.routerInfo().uptimeSeconds();
        for (SsidCounter counter : snapshot.ssidCounters()) {
            saveSample(now, CounterSourceType.SSID, counter.alias(), counter.rxBytes(), counter.txBytes(), uptime);
        }
        for (PortCounter counter : snapshot.portCounters()) {
            saveSample(now, CounterSourceType.LAN_PORT, counter.port(), counter.rxBytes(), counter.txBytes(), uptime);
        }
        // Settings are stored with the poll; API reads never touch the router.
        jdbc.update("delete from router_wifi");
        for (SsidCounter wifi : snapshot.ssidCounters())
            jdbc.update("insert into router_wifi(id,name,enabled,security_mode,channel,band) values(?,?,?,?,?,?)",
                    wifi.alias(), wifi.name(), wifi.enabled(), wifi.securityMode(),
                    wifi.channel() != null ? wifi.channel() : snapshot.devices().stream()
                            .filter(d -> wifi.alias().equals(d.ssid()) && d.channel() != null)
                            .map(RouterDevice::channel).findFirst().orElse(null), wifi.band());
        jdbc.update("delete from router_ports");
        for (PortCounter port : snapshot.portCounters())
            jdbc.update("insert into router_ports(id,status,link_speed_mbps) values(?,?,?)",
                    port.port(), port.status(), port.linkSpeedMbps());
        // Recompute the current hour from all readings; upsert makes repeated polls idempotent.
        rollups.rollUp(now.minusSeconds(3600), now);
        status.setState(RouterClientState.OK);
        status.setLastOkAt(now);
        status.setLastPollAt(now);
        status.setUpdatedAt(clock.instant());
        status.setLastError(null);
        status.setConsecutiveFailures(0);
        status.setModel(snapshot.routerInfo().model());
        status.setFirmware(snapshot.routerInfo().firmware());
        status.setHardware(snapshot.routerInfo().hardware());
        status.setUptimeS(uptime);
        status.setDslDownCurrentKbps(snapshot.dslRates().downCurrentKbps());
        status.setDslDownMaxKbps(snapshot.dslRates().downMaxKbps());
        status.setDslUpCurrentKbps(snapshot.dslRates().upCurrentKbps());
        status.setDslUpMaxKbps(snapshot.dslRates().upMaxKbps());
        statuses.save(status);
    }

    private void saveSample(Instant now, CounterSourceType type, String id, long rx, long tx, Long uptime) {
        if (id == null || id.isBlank()) return;
        if (!samples.existsBySourceTypeAndSourceIdAndTakenAt(type, id, now)) {
            samples.save(CounterSampleEntity.of(now, type, id, rx, tx, uptime));
        }
    }

    private void recordFailure() {
        RouterStatusEntity status = statuses.findById(1).orElseGet(RouterStatusEntity::new);
        status.setState(client.state());
        status.setLastError(client.lastError());
        status.setLastPollAt(clock.instant());
        status.setUpdatedAt(clock.instant());
        status.setConsecutiveFailures(status.getConsecutiveFailures() + 1);
        statuses.save(status);
    }
}
