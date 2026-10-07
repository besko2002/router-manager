package com.example.routermanager.usage;

import java.util.List;

/**
 * The honest small print that every usage answer carries. Written once, so the API, the README and
 * the UI cannot disagree about what these numbers are.
 */
public final class UsageNotes {

    public static final List<String> NOTES = List.of(
            "The ZXHN H188A has no per-device byte counters and no WAN byte counter. "
                    + "These figures come from the router's per-SSID and per-LAN-port counters.",
            "The household total is the sum of the SSID counters and the LAN port counters. "
                    + "Traffic between a Wi-Fi device and a wired device passes both, so it is counted "
                    + "twice; local traffic (file copies, casting, backups) inflates the total.",
            "These are router-side counters, not the figure your ISP meters: they include local "
                    + "traffic and exclude nothing that was retransmitted.",
            "Counters reset to 0 when the router reboots. A reset is detected (the WAN uptime drops "
                    + "or the value falls) and the traffic since the reboot is counted once.",
            "A poll that was missed is not spread over the gap: the whole delta is credited to the "
                    + "hour the interval ended in.");

    private UsageNotes() {
    }
}
