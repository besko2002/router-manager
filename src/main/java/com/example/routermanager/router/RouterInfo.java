package com.example.routermanager.router;

/**
 * Identity and WAN state of the router.
 *
 * @param uptimeSeconds WAN uptime in seconds; a DECREASE means the router rebooted, which is how
 *                      counter resets are detected
 */
public record RouterInfo(
        String model,
        String firmware,
        String hardware,
        Long uptimeSeconds,
        String wanStatus,
        String wanType,
        String wanName) {

    public static RouterInfo empty() {
        return new RouterInfo("", "", "", null, "", "", "");
    }
}
