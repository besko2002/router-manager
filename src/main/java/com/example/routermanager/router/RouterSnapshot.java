package com.example.routermanager.router;

import java.time.Instant;
import java.util.List;

/**
 * Everything one poll reads from the router, at one instant.
 *
 * <p>Deliberately NOT in here: per-device byte counters and a WAN byte counter. The H188A exposes
 * neither (see README "Honest limits"), so nothing here pretends to be the ISP-metered figure.
 */
public record RouterSnapshot(
        RouterInfo routerInfo,
        List<RouterDevice> devices,
        List<SsidCounter> ssidCounters,
        List<PortCounter> portCounters,
        DslRates dslRates,
        Instant takenAt) {

    public RouterSnapshot {
        devices = devices == null ? List.of() : List.copyOf(devices);
        ssidCounters = ssidCounters == null ? List.of() : List.copyOf(ssidCounters);
        portCounters = portCounters == null ? List.of() : List.copyOf(portCounters);
        routerInfo = routerInfo == null ? RouterInfo.empty() : routerInfo;
        dslRates = dslRates == null ? DslRates.empty() : dslRates;
    }

    public List<RouterDevice> devicesOfKind(DeviceKind kind) {
        return devices.stream().filter(device -> device.kind() == kind).toList();
    }
}
