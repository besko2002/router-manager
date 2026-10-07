package com.example.routermanager.monitor;

/** Device history, written only when something actually changed. */
public enum DeviceEventType {

    /** The first time this MAC was ever seen. Implies it was online at that moment. */
    NEW_DEVICE,

    /** A known device came back after having been marked offline. */
    ONLINE,

    /** A known device was missing from two consecutive polls (flapping guard). */
    OFFLINE,

    /** The DHCP lease changed. */
    IP_CHANGED
}
