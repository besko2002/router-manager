package com.example.routermanager.api;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;

class LoginLimiterTest {
    static class AdjustableClock extends Clock {
        Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
        void advance(Duration delta) { instant = instant.plus(delta); }
    }
    @Test void fiveFailuresBlockUntilSlidingWindowPasses() {
        var clock = new AdjustableClock();
        var limiter = new LoginLimiter(clock);
        for (int i = 0; i < 5; i++) assertThat(limiter.retryAfter("owner", "127.0.0.1")).isZero();
        for (int i = 0; i < 5; i++) limiter.fail("owner", "127.0.0.1");
        assertThat(limiter.retryAfter("owner", "127.0.0.1")).isPositive();
        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.retryAfter("owner", "127.0.0.1")).isZero();
    }
    @Test void usernamesAndIpAddressesHaveIndependentLimits() {
        var limiter = new LoginLimiter(new AdjustableClock());
        for (int i = 0; i < 5; i++) limiter.fail("owner", "one");
        assertThat(limiter.retryAfter("owner", "two")).isZero();
        assertThat(limiter.retryAfter("other", "one")).isZero();
    }
    @Test void successfulLoginClearsFailures() {
        var limiter = new LoginLimiter(new AdjustableClock());
        for (int i = 0; i < 5; i++) limiter.fail("owner", "one");
        limiter.success("owner", "one");
        assertThat(limiter.retryAfter("owner", "one")).isZero();
    }
}
