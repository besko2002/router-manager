package com.example.routermanager.simulator;

import com.example.routermanager.router.AjaxDocument;
import com.example.routermanager.router.AjaxInstance;
import com.example.routermanager.router.AjaxXml;
import com.example.routermanager.router.DeviceKind;
import com.example.routermanager.router.DslRates;
import com.example.routermanager.router.MacAddresses;
import com.example.routermanager.router.PortCounter;
import com.example.routermanager.router.RouterDevice;
import com.example.routermanager.router.RouterInfo;
import com.example.routermanager.router.SsidCounter;
import com.example.routermanager.router.ZteResponseParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The simulated router's world: devices, per-SSID and per-port counters, uptime and identity.
 *
 * <p>Seeded from the sanitised fixtures in {@code classpath:/simulator/} — the fixtures are parsed
 * with the production parsers and rendered back into the same XML shapes, so the simulator serves
 * the router's real field names while staying mutable for tests.
 */
public final class SimulatorState {

    static final String FIXTURE_PATH = "/simulator/";

    /** One simulated client. */
    public static final class Device {
        DeviceKind kind;
        String mac;
        String ip;
        String name;
        String ssid;
        String ssidName;
        Integer rssi;
        Integer txRate;
        Integer rxRate;
        Integer channel;
        String mode;
        String port;
        String instanceId;

        Device(RouterDevice source, String instanceId) {
            this.kind = source.kind();
            this.mac = source.mac();
            this.ip = source.ip();
            this.name = source.name();
            this.ssid = source.ssid();
            this.ssidName = source.ssidName();
            this.rssi = source.rssi();
            this.txRate = source.linkTxKbps();
            this.rxRate = source.linkRxKbps();
            this.channel = source.channel();
            this.mode = source.mode();
            this.port = source.port();
            this.instanceId = instanceId;
        }

        public String mac() {
            return mac;
        }

        public DeviceKind kind() {
            return kind;
        }
    }

    /** Mutable SSID counters. */
    public static final class Ssid {
        final String instanceId;
        final String alias;
        String essid;
        boolean enabled;
        long rxBytes;
        long txBytes;
        long rxPackets;
        long txPackets;
        int channel;
        String bssid;

        Ssid(String instanceId, String alias, String essid, boolean enabled) {
            this.instanceId = instanceId;
            this.alias = alias;
            this.essid = essid;
            this.enabled = enabled;
        }
    }

    /** Mutable LAN port counters. */
    public static final class Port {
        final String instanceId;
        final String alias;
        String status;
        Integer linkSpeed;
        String duplex = "Full";
        String mac;
        long rxBytes;
        long txBytes;

        Port(String instanceId, String alias, String status, Integer linkSpeed) {
            this.instanceId = instanceId;
            this.alias = alias;
            this.status = status;
            this.linkSpeed = linkSpeed;
        }
    }

    private final List<Device> devices = new ArrayList<>();
    private final Map<String, Ssid> ssids = new LinkedHashMap<>();
    private final Map<String, Port> ports = new LinkedHashMap<>();
    private final List<AjaxInstance> radios = new ArrayList<>();
    private AjaxDocument dslDocument;
    private DslRates dslRates = DslRates.empty();
    private RouterInfo info = RouterInfo.empty();
    private long uptimeSeconds;
    private String wanStatus = "Connected";
    private String wanType = "pppoe";
    private String wanName = "WAN0";
    private int rebootCount;
    private int nextInstance = 100;

    public SimulatorState() {
        seedFromFixtures();
    }

    // ------------------------------------------------------------------ seeding

    private void seedFromFixtures() {
        AjaxDocument wifi = fixture("accessdev_ssiddev_lua");
        for (RouterDevice device : ZteResponseParser.parseWifiDevices(wifi)) {
            devices.add(new Device(device, "DEV.WIFI.AP1.AD" + (nextInstance++)));
        }
        AjaxDocument wired = fixture("accessdev_landevs_lua");
        for (RouterDevice device : ZteResponseParser.parseWiredDevices(wired)) {
            devices.add(new Device(device, "LANDEV" + (nextInstance++)));
        }

        AjaxDocument wlan = fixture("wlan_status_lua");
        List<AjaxInstance> aps = wlan.instances("OBJ_WLANAP_ID");
        List<SsidCounter> counters = ZteResponseParser.parseSsidCounters(wlan);
        for (int i = 0; i < counters.size(); i++) {
            SsidCounter counter = counters.get(i);
            String instanceId = i < aps.size() ? aps.get(i).get("_InstID") : "DEV.WIFI.AP" + (i + 1);
            Ssid ssid = new Ssid(instanceId, counter.alias(), counter.name(), counter.enabled());
            ssid.rxBytes = counter.rxBytes();
            ssid.txBytes = counter.txBytes();
            ssid.rxPackets = counter.rxPackets();
            ssid.txPackets = counter.txPackets();
            AjaxInstance drv = wlan.instances("OBJ_WLANCONFIGDRV_ID").stream()
                    .filter(candidate -> candidate.get("_InstID").equals(instanceId))
                    .findFirst().orElse(null);
            ssid.channel = drv == null ? 9 : Optional.ofNullable(drv.getIntOrNull("ChannelInUsed")).orElse(9);
            ssid.bssid = drv == null ? "02:00:5e:00:00:07" : drv.get("Bssid");
            ssids.put(ssid.alias, ssid);
        }
        radios.addAll(wlan.instances("OBJ_WLANSETTING_ID"));

        AjaxDocument eth = fixture("eth_lanstatus_lua");
        List<AjaxInstance> ethInstances = eth.instances("OBJ_ETH_ID");
        List<PortCounter> portCounters = ZteResponseParser.parsePortCounters(eth);
        for (int i = 0; i < portCounters.size(); i++) {
            PortCounter counter = portCounters.get(i);
            AjaxInstance instance = ethInstances.get(i);
            Port port = new Port(instance.get("_InstID"), counter.port(), counter.status(),
                    counter.linkSpeedMbps());
            port.rxBytes = counter.rxBytes();
            port.txBytes = counter.txBytes();
            port.duplex = instance.get("LinkDuplex");
            port.mac = instance.get("MACAddress");
            ports.put(port.alias, port);
        }

        dslDocument = fixture("dsl_interface_status_lua");
        dslRates = ZteResponseParser.parseDslRates(dslDocument);

        AjaxDocument statusMgr = fixture("devmgr_statusmgr_lua");
        RouterInfo deviceInfo = ZteResponseParser.parseDeviceInfo(statusMgr);
        AjaxDocument wan = fixture("wan_internet_lua");
        RouterInfo merged = ZteResponseParser.mergeWanInfo(deviceInfo, wan);
        info = merged;
        uptimeSeconds = merged.uptimeSeconds() == null ? 0L : merged.uptimeSeconds();
        wanStatus = merged.wanStatus();
        wanType = merged.wanType();
        wanName = merged.wanName();
    }

    static AjaxDocument fixture(String name) {
        return AjaxXml.parse(fixtureText(name + ".xml"));
    }

    static String fixtureText(String fileName) {
        try (InputStream in = SimulatorState.class.getResourceAsStream(FIXTURE_PATH + fileName)) {
            if (in == null) {
                throw new IllegalStateException("missing simulator fixture " + FIXTURE_PATH + fileName);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read simulator fixture " + fileName, e);
        }
    }

    // ------------------------------------------------------------------ mutation (test hooks)

    public synchronized Device addWifiDevice(String mac, String ip, String name, String ssidAlias, int rssi) {
        String normalized = MacAddresses.normalize(mac);
        removeDevice(normalized);
        Ssid ssid = ssids.get(ssidAlias);
        RouterDevice source = new RouterDevice(DeviceKind.WIFI, normalized, ip, name, ssidAlias,
                ssid == null ? ssidAlias : ssid.essid, rssi, 130000, 130000,
                ssid == null ? 9 : ssid.channel, "11n", null);
        Device device = new Device(source, "DEV.WIFI.AP1.AD" + (nextInstance++));
        devices.add(device);
        return device;
    }

    public synchronized Device addWiredDevice(String mac, String ip, String name, String port) {
        String normalized = MacAddresses.normalize(mac);
        removeDevice(normalized);
        RouterDevice source = new RouterDevice(DeviceKind.WIRED, normalized, ip, name, null, null,
                null, null, null, null, null, port);
        Device device = new Device(source, "LANDEV" + (nextInstance++));
        devices.add(device);
        return device;
    }

    public synchronized boolean removeDevice(String mac) {
        String normalized = MacAddresses.normalize(mac);
        return devices.removeIf(device -> device.mac.equals(normalized));
    }

    public synchronized void setDeviceIp(String mac, String ip) {
        String normalized = MacAddresses.normalize(mac);
        devices.stream().filter(device -> device.mac.equals(normalized))
                .forEach(device -> device.ip = ip);
    }

    public synchronized List<String> deviceMacs() {
        return devices.stream().map(device -> device.mac).toList();
    }

    public synchronized void advanceSsidBytes(String alias, long rx, long tx) {
        Ssid ssid = ssids.get(alias);
        if (ssid == null) {
            throw new IllegalArgumentException("unknown SSID " + alias);
        }
        ssid.rxBytes += rx;
        ssid.txBytes += tx;
        ssid.rxPackets += Math.max(rx / 1500, 0);
        ssid.txPackets += Math.max(tx / 1500, 0);
    }

    public synchronized void advancePortBytes(String port, long rx, long tx) {
        Port state = ports.get(port);
        if (state == null) {
            throw new IllegalArgumentException("unknown LAN port " + port);
        }
        state.rxBytes += rx;
        state.txBytes += tx;
    }

    public synchronized void setSsidBytes(String alias, long rx, long tx) {
        Ssid ssid = ssids.get(alias);
        if (ssid == null) {
            throw new IllegalArgumentException("unknown SSID " + alias);
        }
        ssid.rxBytes = rx;
        ssid.txBytes = tx;
    }

    public synchronized void setPortBytes(String port, long rx, long tx) {
        Port state = ports.get(port);
        if (state == null) {
            throw new IllegalArgumentException("unknown LAN port " + port);
        }
        state.rxBytes = rx;
        state.txBytes = tx;
    }

    public synchronized void setPortStatus(String port, String status, Integer linkSpeed) {
        Port state = ports.get(port);
        if (state != null) {
            state.status = status;
            state.linkSpeed = linkSpeed;
        }
    }

    public synchronized void advanceUptime(long seconds) {
        uptimeSeconds += seconds;
    }

    /** A reboot: every cumulative counter restarts at 0 and the uptime goes back to 0. */
    public synchronized void reboot() {
        ssids.values().forEach(ssid -> {
            ssid.rxBytes = 0;
            ssid.txBytes = 0;
            ssid.rxPackets = 0;
            ssid.txPackets = 0;
        });
        ports.values().forEach(port -> {
            port.rxBytes = 0;
            port.txBytes = 0;
        });
        uptimeSeconds = 0;
        rebootCount++;
    }

    public synchronized int rebootCount() {
        return rebootCount;
    }

    public synchronized long uptimeSeconds() {
        return uptimeSeconds;
    }

    public synchronized long ssidRxBytes(String alias) {
        return ssids.get(alias).rxBytes;
    }

    public synchronized long portRxBytes(String port) {
        return ports.get(port).rxBytes;
    }

    public synchronized List<String> ssidAliases() {
        return List.copyOf(ssids.keySet());
    }

    public synchronized List<String> portAliases() {
        return List.copyOf(ports.keySet());
    }

    public synchronized void setWanStatus(String status) {
        this.wanStatus = status;
    }

    // ------------------------------------------------------------------ rendering

    public synchronized String renderWiredDevices() {
        List<AjaxInstance> instances = new ArrayList<>();
        for (Device device : devices) {
            if (device.kind != DeviceKind.WIRED) {
                continue;
            }
            instances.add(AjaxInstance.of(
                    "HostName", device.name,
                    "IPAddress", device.ip,
                    "IPV6Address", "2001:db8::1",
                    "MACAddress", device.mac,
                    "AliasName", device.port));
        }
        return render(builder().container("OBJ_ACCESSDEV_ID", instances).build());
    }

    public synchronized String renderWifiDevices() {
        List<AjaxInstance> instances = new ArrayList<>();
        for (Device device : devices) {
            if (device.kind != DeviceKind.WIFI) {
                continue;
            }
            instances.add(AjaxInstance.of(
                    "_InstID", device.instanceId,
                    "CurrentMode", device.mode,
                    "IfName", device.ssid,
                    "DeviceName", device.name,
                    "RXRate", device.rxRate,
                    "SSIDName", device.ssidName,
                    "ChannelInUsed", device.channel,
                    "RSSI", device.rssi,
                    "ADIPV6Address", "2001:db8::1",
                    "ADIPAddress", device.ip,
                    "TXRate", device.txRate,
                    "ADMACAddress", device.mac,
                    "MCS", "15"));
        }
        return render(builder().container("OBJ_WLANAD_ID", instances).build());
    }

    public synchronized String renderLanStatus() {
        List<AjaxInstance> eth = new ArrayList<>();
        List<AjaxInstance> wanLan = new ArrayList<>();
        for (Port port : ports.values()) {
            eth.add(AjaxInstance.of(
                    "_InstID", port.instanceId,
                    "AliasName", port.alias,
                    "LinkDuplex", port.duplex,
                    "BytesReceived", port.rxBytes,
                    "Status", port.status,
                    "MACAddress", port.mac,
                    "LinkSpeed", port.linkSpeed == null ? "" : port.linkSpeed,
                    "BytesSent", port.txBytes));
            wanLan.add(AjaxInstance.of("_InstID", port.instanceId,
                    "IPAddress", "192.168.1.1", "IPv6Addr", "2001:db8::1"));
        }
        return render(builder()
                .container("OBJ_ETH_ID", eth)
                .container("OBJ_WANLAN_ID", wanLan)
                .build());
    }

    public synchronized String renderWlanStatus() {
        List<AjaxInstance> aps = new ArrayList<>();
        List<AjaxInstance> drivers = new ArrayList<>();
        for (Ssid ssid : ssids.values()) {
            aps.add(AjaxInstance.of(
                    "_InstID", ssid.instanceId,
                    "WPAEncryptType", "TKIPandAESEncryption",
                    "Enable", ssid.enabled ? "1" : "0",
                    "WPAAuthMode", "PSKAuthentication",
                    "BeaconType", "WPAand11i",
                    "WLANViewName", "DEV.WIFI.RD1",
                    "Alias", ssid.alias,
                    "11iEncryptType", "TKIPandAESEncryption",
                    "11iAuthMode", "PSKAuthentication",
                    "ESSID", ssid.essid));
            drivers.add(AjaxInstance.of(
                    "_InstID", ssid.instanceId,
                    "TotalBytesSent", ssid.txBytes,
                    "Bssid", ssid.bssid,
                    "RealRF", "1",
                    "WLANViewName", "DEV.WIFI.RD1",
                    "ChannelInUsed", ssid.channel,
                    "TotalPacketsSent", ssid.txPackets,
                    "TotalPacketsReceived", ssid.rxPackets,
                    "TotalBytesReceived", ssid.rxBytes));
        }
        List<AjaxInstance> settings = radios.stream().map(AjaxInstance::copy).toList();
        return render(builder()
                .container("OBJ_WLANAP_ID", aps)
                .container("OBJ_WLANCONFIGDRV_ID", drivers)
                .container("OBJ_WLANSETTING_ID", new ArrayList<>(settings))
                .build());
    }

    public synchronized String renderDslStatus() {
        return render(dslDocument);
    }

    public synchronized String renderWanInternet() {
        AjaxInstance live = AjaxInstance.of(
                "ConnTrigger", "AlwaysOn",
                "UpTime", uptimeSeconds,
                "IsNAT", "1",
                "wantype", wanType,
                "WANCName", wanName,
                "TransType", "PPPoE",
                "mode", "route",
                "uplink", "1",
                "pageType", "1",
                "_InstID", "IGD.WD1.WCD1.WCPPP1",
                "ConnStatus", wanStatus,
                "ConnError", "ERROR_NONE",
                "MTU", "1492");
        // The router also lists the leftover unconfigured connections; keeping one proves the
        // client picks the live WAN rather than the first instance.
        AjaxInstance leftover = AjaxInstance.of(
                "ConnTrigger", "AlwaysOn",
                "UpTime", "0",
                "wantype", wanType,
                "WANCName", "PVC0",
                "_InstID", "IGD.WD1.WCD2.WCPPP1",
                "ConnStatus", "Unconfigured",
                "ConnError", "ERROR_NO_CARRIER");
        return render(builder().container("ID_WAN_COMFIG", List.of(leftover, live)).build());
    }

    public synchronized String renderStatusMgr() {
        return render(builder().container("OBJ_DEVINFO_ID", List.of(AjaxInstance.of(
                "_InstID", "IGD",
                "VerDate", "20240524183957",
                "SoftwareVer", info.firmware(),
                "ModelName", info.model(),
                "HardwareVer", info.hardware(),
                "SerialNumber", "SN0000000000",
                "BootVer", "V1.0.2"))).build());
    }

    public synchronized String renderArpTable() {
        List<AjaxInstance> instances = new ArrayList<>();
        for (Device device : devices) {
            if (device.ip == null || device.ip.isBlank()) {
                continue;
            }
            instances.add(AjaxInstance.of(
                    "Status", "1",
                    "DestIP", device.ip,
                    "MACAddr", device.mac,
                    "Interface", "LAN"));
        }
        AjaxDocument document = builder()
                .scalar("disp_all", "0")
                .scalar("showInst", "1")
                .scalar("disp_num", String.valueOf(instances.size()))
                .container("OBJ_GETARPINST_ID", instances)
                .build();
        return render(document);
    }

    public synchronized String renderMacTable() {
        List<AjaxInstance> instances = new ArrayList<>();
        for (Device device : devices) {
            String iface = device.kind == DeviceKind.WIFI ? device.ssid : device.port;
            if (iface == null || iface.isBlank()) {
                continue;
            }
            instances.add(AjaxInstance.of(
                    "AgingTm", "299.91",
                    "VlanID", "0",
                    "MACAddr", device.mac,
                    "WanOrLan", "2",
                    "LanName", iface));
        }
        AjaxDocument document = builder()
                .scalar("disp_all", "0")
                .scalar("showInst", "1")
                .scalar("disp_num", String.valueOf(instances.size()))
                .container("OBJ_GETMACINST_ID", instances)
                .build();
        return render(document);
    }

    /** Renders the document the simulator serves for one data endpoint, or null when unknown. */
    public synchronized String renderEndpoint(String fixtureName) {
        return switch (fixtureName.toLowerCase(Locale.ROOT)) {
            case "accessdev_landevs_lua" -> renderWiredDevices();
            case "accessdev_ssiddev_lua" -> renderWifiDevices();
            case "eth_lanstatus_lua" -> renderLanStatus();
            case "wlan_status_lua" -> renderWlanStatus();
            case "dsl_interface_status_lua" -> renderDslStatus();
            case "wan_internet_lua" -> renderWanInternet();
            case "arp_arptable_lua" -> renderArpTable();
            case "macinfo_mactable_lua" -> renderMacTable();
            case "devmgr_statusmgr_lua" -> renderStatusMgr();
            default -> null;
        };
    }

    private static String render(AjaxDocument document) {
        return AjaxXml.render(document);
    }

    private static DocBuilder builder() {
        return new DocBuilder();
    }

    /** Builds the standard {@code SUCC} envelope plus containers, in the router's field order. */
    static final class DocBuilder {
        private final Map<String, String> scalars = new LinkedHashMap<>();
        private final Map<String, List<AjaxInstance>> containers = new LinkedHashMap<>();

        DocBuilder() {
            scalars.put("IF_ERRORPARAM", "SUCC");
            scalars.put("IF_ERRORTYPE", "SUCC");
            scalars.put("IF_ERRORSTR", "SUCC");
            scalars.put("IF_ERRORID", "0");
        }

        DocBuilder scalar(String name, String value) {
            scalars.put(name, value);
            return this;
        }

        DocBuilder container(String name, List<AjaxInstance> instances) {
            containers.put(name, new ArrayList<>(instances));
            return this;
        }

        AjaxDocument build() {
            return new AjaxDocument(scalars, containers, "");
        }
    }
}
