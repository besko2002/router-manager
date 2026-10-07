package com.example.routermanager.api;

import com.example.routermanager.common.BadRequestException;
import com.example.routermanager.common.ResourceNotFoundException;
import com.example.routermanager.monitor.*;
import com.example.routermanager.router.MacAddresses;
import com.example.routermanager.router.RouterClient;
import com.example.routermanager.router.RouterClientState;
import com.example.routermanager.usage.*;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
public class RouterApi {
    private final RouterStatusRepository statuses;
    private final DeviceRepository devices;
    private final DeviceEventRepository events;
    private final RouterClient client;
    private final UsageQueryService usage;
    private final UsageProperties properties;
    private final Clock clock;
    private final SourceLabelRepository labels;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public RouterApi(RouterStatusRepository statuses, DeviceRepository devices, DeviceEventRepository events,
                     RouterClient client, UsageQueryService usage, UsageProperties properties, Clock clock,
                     SourceLabelRepository labels, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.statuses = statuses;
        this.devices = devices;
        this.events = events;
        this.client = client;
        this.usage = usage;
        this.properties = properties;
        this.clock = clock;
        this.labels = labels;
        this.jdbc = jdbc;
    }

    public record Status(String state, Instant lastOkAt, Instant lastPollAt, String lastError,
                         String model, String firmware, Long uptimeS, Integer dslDownCurrentKbps,
                         Integer dslDownMaxKbps, Integer dslUpCurrentKbps, Integer dslUpMaxKbps,
                         long onlineDevices) {}

    @GetMapping("/status")
    public Status status() {
        RouterStatusEntity status = statuses.findById(1).orElseGet(RouterStatusEntity::new);
        RouterClientState state = client.state();
        return new Status(state == RouterClientState.NEW ? status.getState().name() : state.name(),
                status.getLastOkAt(), status.getLastPollAt(), status.getLastError(), status.getModel(),
                status.getFirmware(), status.getUptimeS(), status.getDslDownCurrentKbps(),
                status.getDslDownMaxKbps(), status.getDslUpCurrentKbps(), status.getDslUpMaxKbps(),
                devices.countByOnline(true));
    }

    public record Device(String mac, String name, String vendor, String kind, String ssid, String ip,
                         Integer rssi, Integer linkTxKbps, Integer linkRxKbps, String port,
                         Instant firstSeen, Instant lastSeen, boolean online,
                          String alias, String displayName, boolean trusted) {
        static Device from(DeviceEntity d) {
            String name = d.getAlias() != null ? d.getAlias() :
                    d.getName() != null && !d.getName().isBlank() ? d.getName() : "Unnamed device";
            return new Device(d.getMac(), d.getName(), d.getVendor(), d.getKind().name(), d.getSsid(),
                    d.getLastIp(), d.getLastRssi(), d.getLinkTxKbps(), d.getLinkRxKbps(), d.getPort(),
                    d.getFirstSeen(), d.getLastSeen(), d.isOnline(), d.getAlias(), name, d.isTrusted());
        }
    }

    @GetMapping("/devices")
    public List<Device> devices(@RequestParam(required = false) Boolean online,
                                @RequestParam(required = false) Boolean trusted) {
        return (online == null ? devices.findAllByOrderByLastSeenDesc()
                : devices.findByOnlineOrderByLastSeenDesc(online)).stream()
                .filter(d -> trusted == null || d.isTrusted() == trusted).map(Device::from).toList();
    }

    public record TextValue(String alias) {}
    public record TrustValue(Boolean trusted) {}
    public record LabelValue(String label) {}

    private DeviceEntity knownDevice(String mac) {
        if (!mac.matches("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}") && !mac.matches("(?i)[0-9a-f]{12}"))
            throw new BadRequestException("Malformed MAC address");
        return devices.findById(MacAddresses.normalize(mac))
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));
    }

    private static String text(String value) {
        if (value == null || value.codePoints().anyMatch(Character::isISOControl))
            throw new BadRequestException("Text must be 1–60 characters without control characters");
        String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.codePointCount(0, trimmed.length()) > 60)
            throw new BadRequestException("Text must be 1–60 characters without control characters");
        return trimmed;
    }

    @PutMapping("/devices/{mac}/alias")
    public Device alias(@PathVariable String mac, @RequestBody TextValue value) {
        DeviceEntity device = knownDevice(mac);
        device.setAlias(text(value.alias()));
        return Device.from(devices.save(device));
    }

    @DeleteMapping("/devices/{mac}/alias")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAlias(@PathVariable String mac) {
        DeviceEntity device = knownDevice(mac);
        device.setAlias(null);
        devices.save(device);
    }

    @PutMapping("/devices/{mac}/trusted")
    public Device trust(@PathVariable String mac, @RequestBody TrustValue value) {
        if (value.trusted() == null) throw new BadRequestException("trusted is required");
        DeviceEntity device = knownDevice(mac);
        device.setTrusted(value.trusted());
        device.setTrustedAt(value.trusted() ? clock.instant() : null);
        return Device.from(devices.save(device));
    }

    private CounterSourceType sourceType(String type) {
        try { return CounterSourceType.valueOf(type.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new BadRequestException("type must be SSID or LAN_PORT"); }
    }

    @PutMapping("/labels/{type}/{id}")
    public LabelValue label(@PathVariable String type, @PathVariable String id, @RequestBody LabelValue value) {
        CounterSourceType sourceType = sourceType(type);
        if (id.isBlank() || id.length() > 32) throw new BadRequestException("Invalid source id");
        SourceLabel row = labels.findBySourceTypeAndSourceId(sourceType, id)
                .orElseGet(() -> new SourceLabel(sourceType, id, ""));
        row.setLabel(text(value.label()));
        labels.save(row);
        return new LabelValue(row.getLabel());
    }

    @DeleteMapping("/labels/{type}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLabel(@PathVariable String type, @PathVariable String id) {
        labels.findBySourceTypeAndSourceId(sourceType(type), id).ifPresent(labels::delete);
    }

    public record Event(Instant at, String type, String details) {}

    @GetMapping("/devices/{mac}/events")
    public List<Event> events(@PathVariable String mac) {
        if (!mac.matches("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}") && !mac.matches("(?i)[0-9a-f]{12}"))
            throw new BadRequestException("Malformed MAC address");
        String normalized = MacAddresses.normalize(mac);
        if (!devices.existsById(normalized)) throw new ResourceNotFoundException("Device not found");
        return events.findByMacOrderByAtDesc(normalized, Limit.of(500)).stream()
                .map(e -> new Event(e.getAt(), e.getType().name(), e.getDetails())).toList();
    }

    public record Bytes(long rxBytes, long txBytes) {}
    public record Source(String id, String name, String label, long rxBytes, long txBytes) {}
    public record Summary(Instant from, Instant to, boolean approximate, List<String> notes, Bytes total,
                          List<Source> bySsid, List<Source> byPort) {}

    @GetMapping("/usage/summary")
    public Summary summary(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        Instant end = parse(to, clock.instant(), true);
        Instant start = parse(from, end.minus(Duration.ofDays(1)), false);
        UsageQueryService.Summary result = usage.summary(start, end);
        return new Summary(result.from(), result.to(), true, UsageNotes.NOTES,
                new Bytes(result.rxBytes(), result.txBytes()), result.bySsid().stream().map(this::source).toList(),
                result.byPort().stream().map(this::source).toList());
    }

    private Source source(UsageQueryService.SourceUsage usage) {
        return new Source(usage.sourceId(), usage.sourceId(), labelFor(usage.sourceType(), usage.sourceId()),
                usage.rxBytes(), usage.txBytes());
    }

    private String labelFor(CounterSourceType type, String id) {
        return labels.findBySourceTypeAndSourceId(type, id).map(SourceLabel::getLabel).orElse(null);
    }

    public record Point(String at, long rxBytes, long txBytes) {}
    public record Series(String id, String name, String label, List<Point> points) {}
    public record Timeseries(String granularity, String groupBy, List<Series> series) {}

    @GetMapping("/usage/timeseries")
    public Timeseries timeseries(@RequestParam(defaultValue = "hour") String granularity,
                                 @RequestParam(defaultValue = "total") String groupBy,
                                 @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to) {
        UsageQueryService.Granularity unit;
        UsageQueryService.GroupBy group;
        try {
            unit = UsageQueryService.Granularity.valueOf(granularity.toUpperCase(Locale.ROOT));
            group = UsageQueryService.GroupBy.valueOf(groupBy.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("granularity must be hour|day and groupBy must be total|ssid|port");
        }
        Instant end = parse(to, clock.instant(), true);
        Instant start = parse(from, end.minus(Duration.ofDays(1)), false);
        if (Duration.between(start, end).compareTo(Duration.ofDays(unit == UsageQueryService.Granularity.HOUR ? 14 : 400)) > 0)
            throw new BadRequestException("Range exceeds " + (unit == UsageQueryService.Granularity.HOUR ? 14 : 400) + " days");
        UsageQueryService.Timeseries result = usage.timeseries(unit, group, start, end);
        return new Timeseries(granularity.toLowerCase(Locale.ROOT), groupBy.toLowerCase(Locale.ROOT),
                result.series().stream().map(s -> new Series(s.sourceId(), s.sourceId(),
                        "TOTAL".equals(s.sourceType()) ? null : labelFor(CounterSourceType.valueOf(s.sourceType()), s.sourceId()),
                        s.points().stream().map(p -> new Point(p.bucket(), p.rxBytes(), p.txBytes())).toList())).toList());
    }

    private Instant parse(String value, Instant fallback, boolean end) {
        if (value == null) return fallback;
        try {
            if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate day = LocalDate.parse(value);
                return (end ? day.plusDays(1) : day).atStartOfDay(properties.getZone()).toInstant();
            }
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("Invalid ISO date: " + value);
        }
    }

    public record Wifi(String id, String name, boolean enabled, String label, String securityMode,
                       Integer channel, String band, long connectedDeviceCount) {}
    public record Port(String id, String status, Integer linkSpeedMbps, String label) {}
    public record ReadOnlySettings<T>(boolean readOnly, String note, List<T> items) {}
    private static final String SETTINGS_NOTE = "Read-only: this app never changes your router settings";

    @GetMapping("/router/wifi")
    public ReadOnlySettings<Wifi> wifi() {
        return new ReadOnlySettings<>(true, SETTINGS_NOTE, jdbc.query(
                "select id,name,enabled,security_mode,channel,band from router_wifi order by id",
                (rs, index) -> {
                    String id = rs.getString("id");
                    return new Wifi(id, rs.getString("name"), rs.getBoolean("enabled"),
                            labelFor(CounterSourceType.SSID, id), rs.getString("security_mode"),
                            (Integer) rs.getObject("channel"), rs.getString("band"),
                            devices.findAllByOrderByLastSeenDesc().stream()
                                    .filter(d -> d.isOnline() && id.equals(d.getSsid())).count());
                }));
    }

    @GetMapping("/router/ports")
    public ReadOnlySettings<Port> ports() {
        return new ReadOnlySettings<>(true, SETTINGS_NOTE, jdbc.query(
                "select id,status,link_speed_mbps from router_ports order by id",
                (rs, index) -> new Port(rs.getString("id"), rs.getString("status"),
                        (Integer) rs.getObject("link_speed_mbps"),
                        labelFor(CounterSourceType.LAN_PORT, rs.getString("id")))));
    }

    @PostMapping("/admin/router/reset-auth")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetAuth() {
        client.resetAuth();
    }
}
