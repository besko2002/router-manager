package com.example.routermanager.monitor;

/**
 * What a counter belongs to. The router offers these two and nothing else — in particular there is
 * no per-device and no WAN counter.
 */
public enum CounterSourceType {
    SSID,
    LAN_PORT
}
