package com.example.routermanager.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;

class AppJwtTest {
    @Test void expiresAndRejectsTampering() {
        class MutableClock extends Clock {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        var clock = new MutableClock();
        var jwt = new AppJwt("test-secret-with-at-least-thirty-two-bytes", Duration.ofMinutes(2),
                clock, new ObjectMapper());
        String token = jwt.issue(new AppUser("owner", "hash", clock.instant()));
        assertThat(jwt.verify(token)).contains("owner");
        assertThat(jwt.verify(token + "x")).isEmpty();
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(jwt.verify(token)).isEmpty();
    }
}
