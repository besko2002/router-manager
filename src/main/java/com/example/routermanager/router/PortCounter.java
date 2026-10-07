package com.example.routermanager.router;

/**
 * Cumulative traffic counters of one LAN port, from the router's point of view
 * ({@code rxBytes} = received by the router from the device = the device's upload).
 *
 * @param port alias, {@code LAN1}…{@code LAN4}
 * @param status {@code Up} or {@code NoLink}
 * @param linkSpeedMbps negotiated speed in Mbit/s, null when unknown
 */
public record PortCounter(
        String port,
        String status,
        Integer linkSpeedMbps,
        long rxBytes,
        long txBytes) {

    public boolean up() {
        return "Up".equalsIgnoreCase(status);
    }
}
