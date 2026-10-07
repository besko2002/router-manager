package com.example.routermanager.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test moves by hand. Essential for the login-safety rules: waiting ten real minutes
 * for {@code router.min-relogin-interval} is not a test.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant now;

    private MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public static MutableClock at(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant), ZoneOffset.UTC);
    }

    public static MutableClock now() {
        return new MutableClock(Instant.now(), ZoneOffset.UTC);
    }

    public MutableClock advance(Duration amount) {
        now = now.plus(amount);
        return this;
    }

    public MutableClock set(Instant instant) {
        now = instant;
        return this;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
