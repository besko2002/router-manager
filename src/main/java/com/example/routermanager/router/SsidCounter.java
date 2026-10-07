package com.example.routermanager.router;

/**
 * Cumulative traffic counters of one SSID, as the router counts them.
 *
 * <p>Direction is the ROUTER's point of view: {@code rxBytes} is what the router received from the
 * clients on this SSID (their upload). Counters reset to 0 when the router reboots.
 *
 * @param alias interface alias, {@code SSID1}…{@code SSID8}
 * @param name the broadcast ESSID
 */
public record SsidCounter(
        String alias,
        String name,
        boolean enabled,
        long rxBytes,
        long txBytes,
        long rxPackets,
        long txPackets,
        String securityMode,
        Integer channel,
        String band) {
    public SsidCounter(String alias, String name, boolean enabled, long rxBytes, long txBytes,
                       long rxPackets, long txPackets) {
        this(alias, name, enabled, rxBytes, txBytes, rxPackets, txPackets, null, null, null);
    }
}
