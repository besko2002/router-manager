package com.example.routermanager.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Moves the simulated router's world forward: counters grow, the uptime advances, and a guest
 * device comes and goes — so a demo run produces non-zero, changing usage.
 */
public class DemoTrafficGenerator {

    private static final Logger log = LoggerFactory.getLogger(DemoTrafficGenerator.class);

    private static final String GUEST_MAC = "02:00:5e:00:00:aa";

    private final RouterSimulator simulator;
    private final Random random = new Random(7);
    private final AtomicInteger ticks = new AtomicInteger();

    public DemoTrafficGenerator(RouterSimulator simulator) {
        this.simulator = simulator;
    }

    /** Runs independently of the poller; 2 s keeps a 5 s poll interval interesting. */
    @Scheduled(fixedDelayString = "${demo.traffic-interval:2000}")
    public void tick() {
        SimulatorState state = simulator.state();
        int tick = ticks.incrementAndGet();
        state.advanceUptime(2);

        for (String alias : List.of("SSID1", "SSID5")) {
            state.advanceSsidBytes(alias, 400_000 + random.nextInt(2_000_000),
                    80_000 + random.nextInt(400_000));
        }
        state.advancePortBytes("LAN2", 150_000 + random.nextInt(900_000),
                40_000 + random.nextInt(200_000));

        if (tick % 15 == 0) {
            state.addWifiDevice(GUEST_MAC, "192.168.1.77", "guest-phone", "SSID1", -70 + random.nextInt(10));
            log.info("Demo: guest device joined");
        } else if (tick % 15 == 7) {
            state.removeDevice(GUEST_MAC);
            log.info("Demo: guest device left");
        }
    }
}
