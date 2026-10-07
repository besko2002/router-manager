package com.example.routermanager.router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the router's ajax documents into the domain model.
 *
 * <p>Every method tolerates missing containers, missing fields and junk numbers: a firmware that
 * drops one field must degrade that field, not the whole poll.
 */
public final class ZteResponseParser {

    private ZteResponseParser() {
    }

    // ------------------------------------------------------------------ devices

    /** Wi-Fi clients from {@code accessdev_ssiddev_lua}. */
    public static List<RouterDevice> parseWifiDevices(AjaxDocument document) {
        List<RouterDevice> devices = new ArrayList<>();
        for (AjaxInstance instance : document.instancesOfAny("OBJ_WLANAD_ID", "OBJ_ACCESSDEV_ID")) {
            String mac = MacAddresses.normalize(instance.get("ADMACAddress"));
            if (mac.isEmpty()) {
                continue;
            }
            devices.add(new RouterDevice(
                    DeviceKind.WIFI,
                    mac,
                    instance.get("ADIPAddress"),
                    instance.get("DeviceName"),
                    emptyToNull(instance.get("IfName")),
                    emptyToNull(instance.get("SSIDName")),
                    instance.getIntOrNull("RSSI"),
                    instance.getIntOrNull("TXRate"),
                    instance.getIntOrNull("RXRate"),
                    instance.getIntOrNull("ChannelInUsed"),
                    emptyToNull(instance.get("CurrentMode")),
                    null));
        }
        return devices;
    }

    /** Wired clients from {@code accessdev_landevs_lua}. */
    public static List<RouterDevice> parseWiredDevices(AjaxDocument document) {
        List<RouterDevice> devices = new ArrayList<>();
        for (AjaxInstance instance : document.instancesOfAny("OBJ_ACCESSDEV_ID", "OBJ_LANDEV_ID")) {
            String mac = MacAddresses.normalize(instance.get("MACAddress"));
            if (mac.isEmpty()) {
                continue;
            }
            devices.add(new RouterDevice(
                    DeviceKind.WIRED,
                    mac,
                    instance.get("IPAddress"),
                    instance.get("HostName"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    emptyToNull(instance.get("AliasName"))));
        }
        return devices;
    }

    // ------------------------------------------------------------------ counters

    /**
     * SSID counters from {@code wlan_status_lua}: the identity lives in {@code OBJ_WLANAP_ID}
     * (Alias/ESSID/Enable) and the counters in {@code OBJ_WLANCONFIGDRV_ID}, joined on
     * {@code _InstID} ({@code DEV.WIFI.APn}).
     */
    public static List<SsidCounter> parseSsidCounters(AjaxDocument document) {
        Map<String, AjaxInstance> countersById = new LinkedHashMap<>();
        for (AjaxInstance instance : document.instances("OBJ_WLANCONFIGDRV_ID")) {
            String id = instance.get("_InstID");
            if (!id.isEmpty()) {
                countersById.putIfAbsent(id, instance);
            }
        }

        List<SsidCounter> counters = new ArrayList<>();
        for (AjaxInstance ap : document.instances("OBJ_WLANAP_ID")) {
            String id = ap.get("_InstID");
            String alias = ap.get("Alias");
            if (alias.isEmpty()) {
                alias = id;
            }
            if (alias.isEmpty()) {
                continue;
            }
            AjaxInstance counter = countersById.get(id);
            counters.add(new SsidCounter(
                    alias,
                    ap.get("ESSID"),
                    ap.getFlag("Enable"),
                    counter == null ? 0L : nonNegative(counter.getLong("TotalBytesReceived", 0L)),
                    counter == null ? 0L : nonNegative(counter.getLong("TotalBytesSent", 0L)),
                    counter == null ? 0L : nonNegative(counter.getLong("TotalPacketsReceived", 0L)),
                    counter == null ? 0L : nonNegative(counter.getLong("TotalPacketsSent", 0L)),
                     emptyToNull(ap.get("BeaconType")), ap.getIntOrNull("Channel"),
                     emptyToNull(ap.get("Band"))));
        }
        return counters;
    }

    /** LAN port counters from {@code eth_lanstatus_lua}. */
    public static List<PortCounter> parsePortCounters(AjaxDocument document) {
        List<PortCounter> counters = new ArrayList<>();
        for (AjaxInstance instance : document.instances("OBJ_ETH_ID")) {
            String port = instance.get("AliasName");
            if (port.isEmpty()) {
                port = instance.get("_InstID");
            }
            if (port.isEmpty()) {
                continue;
            }
            counters.add(new PortCounter(
                    port,
                    instance.get("Status"),
                    instance.getIntOrNull("LinkSpeed"),
                    nonNegative(instance.getLong("BytesReceived", 0L)),
                    nonNegative(instance.getLong("BytesSent", 0L))));
        }
        return counters;
    }

    // ------------------------------------------------------------------ router itself

    /** DSL sync rates from {@code dsl_interface_status_lua} (kbit/s). */
    public static DslRates parseDslRates(AjaxDocument document) {
        List<AjaxInstance> instances = document.instancesOfAny("OBJ_DSLINTERFACE_ID");
        if (instances.isEmpty()) {
            return DslRates.empty();
        }
        AjaxInstance line = instances.get(0);
        return new DslRates(
                line.getIntOrNull("Downstream_current_rate"),
                line.getIntOrNull("Downstream_max_rate"),
                line.getIntOrNull("Upstream_current_rate"),
                line.getIntOrNull("Upstream_max_rate"),
                line.get("Status"));
    }

    /** Model/firmware/hardware from {@code devmgr_statusmgr_lua}. The serial is NOT read. */
    public static RouterInfo parseDeviceInfo(AjaxDocument document) {
        List<AjaxInstance> instances = document.instancesOfAny("OBJ_DEVINFO_ID");
        if (instances.isEmpty()) {
            return RouterInfo.empty();
        }
        AjaxInstance info = instances.get(0);
        return new RouterInfo(
                info.get("ModelName"),
                info.get("SoftwareVer"),
                info.get("HardwareVer"),
                null, "", "", "");
    }

    /**
     * WAN state from {@code wan_internet_lua}. The reply lists every configured WAN connection
     * (often one live PPPoE plus unconfigured leftovers); the connected one with the largest
     * uptime wins, otherwise the first instance.
     */
    public static RouterInfo mergeWanInfo(RouterInfo base, AjaxDocument document) {
        List<AjaxInstance> instances = document.instancesOfAny("ID_WAN_COMFIG", "OBJ_WAN_ID");
        if (instances.isEmpty()) {
            instances = document.allInstances();
        }
        AjaxInstance best = null;
        long bestUptime = -1;
        for (AjaxInstance instance : instances) {
            boolean connected = "Connected".equalsIgnoreCase(instance.get("ConnStatus"));
            long uptime = instance.getLong("UpTime", 0L);
            if (best == null) {
                best = instance;
                bestUptime = connected ? uptime : -1;
            } else if (connected && uptime > bestUptime) {
                best = instance;
                bestUptime = uptime;
            }
        }
        if (best == null) {
            return base;
        }
        Long uptime = best.getLongOrNull("UpTime");
        return new RouterInfo(
                base.model(),
                base.firmware(),
                base.hardware(),
                uptime == null || uptime < 0 ? null : uptime,
                best.get("ConnStatus"),
                best.get("wantype"),
                best.get("WANCName"));
    }

    // ------------------------------------------------------------------ enrichment tables

    /** {@code arp_arptable_lua}: normalised MAC to IP. Used only to fill an IP the router omitted. */
    public static Map<String, String> parseArpTable(AjaxDocument document) {
        Map<String, String> byMac = new LinkedHashMap<>();
        for (AjaxInstance instance : document.instancesOfAny("OBJ_GETARPINST_ID", "OBJ_ARP_ID")) {
            String mac = MacAddresses.normalize(instance.get("MACAddr"));
            String ip = instance.get("DestIP");
            if (!mac.isEmpty() && !ip.isEmpty()) {
                byMac.putIfAbsent(mac, ip);
            }
        }
        return byMac;
    }

    /**
     * {@code macinfo_mactable_lua}: normalised MAC to the interface it was learned on
     * ({@code LAN2}, {@code SSID1}…). Used only to fill a missing port/SSID.
     */
    public static Map<String, String> parseMacTable(AjaxDocument document) {
        Map<String, String> byMac = new LinkedHashMap<>();
        for (AjaxInstance instance : document.instancesOfAny("OBJ_GETMACINST_ID", "OBJ_MAC_ID")) {
            String mac = MacAddresses.normalize(instance.get("MACAddr"));
            String iface = instance.get("LanName");
            if (!mac.isEmpty() && !iface.isEmpty()) {
                byMac.putIfAbsent(mac, iface);
            }
        }
        return byMac;
    }

    /**
     * Merges Wi-Fi and wired lists into one per-MAC list and fills the gaps from the ARP and MAC
     * tables. A MAC seen on both sides keeps its Wi-Fi record (the richer one).
     */
    public static List<RouterDevice> mergeDevices(List<RouterDevice> wifi,
                                                  List<RouterDevice> wired,
                                                  Map<String, String> arpByMac,
                                                  Map<String, String> ifaceByMac) {
        Map<String, RouterDevice> byMac = new LinkedHashMap<>();
        for (RouterDevice device : wifi) {
            byMac.putIfAbsent(device.mac(), device);
        }
        for (RouterDevice device : wired) {
            byMac.putIfAbsent(device.mac(), device);
        }

        List<RouterDevice> merged = new ArrayList<>(byMac.size());
        for (RouterDevice device : byMac.values()) {
            RouterDevice result = device;
            if (isBlank(result.ip())) {
                String ip = arpByMac.get(result.mac());
                if (ip != null) {
                    result = result.withIp(ip);
                }
            }
            String iface = ifaceByMac.get(result.mac());
            if (iface != null) {
                String upper = iface.toUpperCase(Locale.ROOT);
                if (result.kind() == DeviceKind.WIRED && isBlank(result.port()) && upper.startsWith("LAN")) {
                    result = result.withPort(iface);
                } else if (result.kind() == DeviceKind.WIFI && isBlank(result.ssid()) && upper.startsWith("SSID")) {
                    result = result.withSsid(iface);
                }
            }
            merged.add(result);
        }
        return merged;
    }

    // ------------------------------------------------------------------ helpers

    private static long nonNegative(long value) {
        return Math.max(value, 0L);
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
