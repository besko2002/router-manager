package com.example.routermanager.router;

/**
 * One client seen by the router.
 *
 * <p>The router reports NO byte counters per device. {@code linkTxKbps}/{@code linkRxKbps} are the
 * negotiated Wi-Fi link rates, not traffic.
 *
 * @param mac normalised lower-case colon form, the identity of the device
 * @param name the router's own label; frequently empty
 * @param ssid SSID interface name for Wi-Fi clients ({@code SSID1}…), null for wired
 * @param port LAN port alias for wired clients ({@code LAN1}…{@code LAN4}), null for Wi-Fi
 */
public record RouterDevice(
        DeviceKind kind,
        String mac,
        String ip,
        String name,
        String ssid,
        String ssidName,
        Integer rssi,
        Integer linkTxKbps,
        Integer linkRxKbps,
        Integer channel,
        String mode,
        String port) {

    public RouterDevice withIp(String newIp) {
        return new RouterDevice(kind, mac, newIp, name, ssid, ssidName, rssi, linkTxKbps, linkRxKbps,
                channel, mode, port);
    }

    public RouterDevice withPort(String newPort) {
        return new RouterDevice(kind, mac, ip, name, ssid, ssidName, rssi, linkTxKbps, linkRxKbps,
                channel, mode, newPort);
    }

    public RouterDevice withSsid(String newSsid) {
        return new RouterDevice(kind, mac, ip, name, newSsid, ssidName, rssi, linkTxKbps, linkRxKbps,
                channel, mode, port);
    }
}
