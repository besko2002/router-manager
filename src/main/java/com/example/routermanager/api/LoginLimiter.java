package com.example.routermanager.api;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Sliding 15-minute window, keyed by normalized account and actual client IP. */
@Component
public class LoginLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> attempts = new HashMap<>();
    public LoginLimiter(Clock clock) { this.clock = clock; }

    public synchronized long retryAfter(String username, String ip) {
        ArrayDeque<Instant> failures = get(username, ip);
        if (failures.size() < 5) return 0;
        return Math.max(1, Duration.between(clock.instant(), failures.peekFirst().plus(WINDOW)).toSeconds() + 1);
    }
    /** Shared per-IP budget for public setup and setup-status requests. */
    public synchronized long setupRetryAfter(String ip) {
        ArrayDeque<Instant> requests = get("\u0001setup", ip);
        if (requests.size() < 30) return 0;
        return Math.max(1, Duration.between(clock.instant(), requests.peekFirst().plus(WINDOW)).toSeconds() + 1);
    }
    public synchronized void setupRequest(String ip) { get("\u0001setup", ip).addLast(clock.instant()); }
    public synchronized void fail(String username, String ip) { get(username, ip).addLast(clock.instant()); }
    public synchronized void success(String username, String ip) { attempts.remove(username + "\u0000" + ip); }

    private ArrayDeque<Instant> get(String username, String ip) {
        String key = username + "\u0000" + ip;
        ArrayDeque<Instant> failures = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!failures.isEmpty() && !failures.peekFirst().isAfter(cutoff)) failures.removeFirst();
        return failures;
    }
}
