package com.example.routermanager.usage;

import com.example.routermanager.usage.UsageCalculator.Delta;
import com.example.routermanager.usage.UsageCalculator.Reading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The delta rules, including every way the router's counters misbehave. */
class UsageCalculatorTest {

    private static final Instant T0 = Instant.parse("2025-03-01T10:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofMinutes(1));

    /** 2 GB/s: anything above that in one minute is a broken reading, not traffic. */
    private final UsageCalculator calculator = new UsageCalculator(2_000_000_000L);

    @Test
    @DisplayName("the first reading of a source produces no traffic")
    void firstSampleHasNoDelta() {
        assertThat(calculator.delta(null, Reading.of(T0, 100, 200, 500))).isEmpty();
    }

    @Test
    @DisplayName("a null current reading produces nothing")
    void nullCurrentHasNoDelta() {
        assertThat(calculator.delta(Reading.of(T0, 1, 2, 3), null)).isEmpty();
    }

    @Test
    @DisplayName("growing counters give the difference")
    void normalGrowth() {
        Delta delta = delta(Reading.of(T0, 1_000, 2_000, 500), Reading.of(T1, 1_500, 2_750, 560));

        assertThat(delta.rxBytes()).isEqualTo(500);
        assertThat(delta.txBytes()).isEqualTo(750);
        assertThat(delta.totalBytes()).isEqualTo(1_250);
        assertThat(delta.counterReset()).isFalse();
        assertThat(delta.wrapped()).isFalse();
        assertThat(delta.dropped()).isFalse();
    }

    @Test
    @DisplayName("unchanged counters give zero, not a reset")
    void equalValues() {
        Delta delta = delta(Reading.of(T0, 1_000, 2_000, 500), Reading.of(T1, 1_000, 2_000, 560));

        assertThat(delta.rxBytes()).isZero();
        assertThat(delta.txBytes()).isZero();
        assertThat(delta.counterReset()).isFalse();
    }

    @Test
    @DisplayName("a reboot (uptime dropped) makes the new value the delta")
    void rebootDetectedByUptime() {
        Delta delta = delta(
                Reading.of(T0, 5_000_000, 9_000_000, 90_000),
                Reading.of(T1, 12_000, 3_000, 42));

        assertThat(delta.rxBytes()).isEqualTo(12_000);
        assertThat(delta.txBytes()).isEqualTo(3_000);
        assertThat(delta.counterReset()).isTrue();
        assertThat(delta.dropped()).isFalse();
    }

    @Test
    @DisplayName("a reboot is honoured even when the counter happens to be HIGHER than before")
    void rebootWithHigherValue() {
        // The uptime is the authority: after a reboot the value is traffic since the reboot, even
        // if the previous value was lower.
        Delta delta = delta(Reading.of(T0, 100, 100, 90_000), Reading.of(T1, 900, 800, 30));

        assertThat(delta.rxBytes()).isEqualTo(900);
        assertThat(delta.txBytes()).isEqualTo(800);
        assertThat(delta.counterReset()).isTrue();
    }

    @Test
    @DisplayName("a value that drops without a reboot is treated as a cleared counter")
    void dropWithoutRebootIsAReset() {
        Delta delta = delta(
                Reading.of(T0, 3_000_000_000L, 2_000_000_000L, 500),
                Reading.of(T1, 12_000_000, 11_000, 560));

        assertThat(delta.rxBytes()).isEqualTo(12_000_000);
        assertThat(delta.txBytes()).isEqualTo(11_000);
        assertThat(delta.counterReset()).isTrue();
        assertThat(delta.wrapped()).isFalse();
    }

    @Test
    @DisplayName("deltas are never negative")
    void neverNegative() {
        Delta delta = delta(Reading.of(T0, 999, 999, 500), Reading.of(T1, 1, 2, 560));

        assertThat(delta.rxBytes()).isNotNegative();
        assertThat(delta.txBytes()).isNotNegative();
    }

    @Test
    @DisplayName("a missing uptime still yields a sane delta")
    void missingUptime() {
        Delta growing = delta(new Reading(T0, 100, 100, null), new Reading(T1, 300, 400, null));
        assertThat(growing.rxBytes()).isEqualTo(200);
        assertThat(growing.counterReset()).isFalse();

        Delta dropped = delta(new Reading(T0, 5_000, 5_000, null), new Reading(T1, 10, 20, null));
        assertThat(dropped.rxBytes()).isEqualTo(10);
        assertThat(dropped.counterReset()).isTrue();
    }

    @Test
    @DisplayName("a 32-bit wrap is recognised only just below 2^32")
    void wrapNearTheCeiling() {
        long justBelow = UsageCalculator.WRAP_AT - 1_000;

        Delta delta = delta(Reading.of(T0, justBelow, justBelow, 500), Reading.of(T1, 500, 200, 560));

        assertThat(delta.rxBytes()).isEqualTo(1_500);
        assertThat(delta.txBytes()).isEqualTo(1_200);
        assertThat(delta.wrapped()).isTrue();
        assertThat(delta.counterReset()).isFalse();
    }

    @Test
    @DisplayName("a drop from far below 2^32 is a reset, not a wrap")
    void noWrapFarFromTheCeiling() {
        long farBelow = UsageCalculator.WRAP_AT - UsageCalculator.WRAP_MARGIN - 1;

        Delta delta = delta(Reading.of(T0, farBelow, farBelow, 500), Reading.of(T1, 100, 100, 560));

        assertThat(delta.wrapped()).isFalse();
        assertThat(delta.counterReset()).isTrue();
        assertThat(delta.rxBytes()).isEqualTo(100);
    }

    @Test
    @DisplayName("a drop to a LARGE value is never a wrap, however high the previous value was")
    void noWrapWhenTheNewValueIsLarge() {
        long justBelow = UsageCalculator.WRAP_AT - 1_000;
        long large = UsageCalculator.WRAP_MARGIN + 1;

        Delta delta = delta(Reading.of(T0, justBelow, justBelow, 500), Reading.of(T1, large, large, 560));

        assertThat(delta.wrapped()).isFalse();
        assertThat(delta.counterReset()).isTrue();
        assertThat(delta.rxBytes()).isEqualTo(large);
    }

    @Test
    @DisplayName("a reboot wins over a wrap: the uptime is the stronger signal")
    void rebootBeatsWrap() {
        long justBelow = UsageCalculator.WRAP_AT - 1_000;

        Delta delta = delta(Reading.of(T0, justBelow, justBelow, 90_000), Reading.of(T1, 500, 500, 10));

        assertThat(delta.wrapped()).isFalse();
        assertThat(delta.counterReset()).isTrue();
        assertThat(delta.rxBytes()).isEqualTo(500);
    }

    @Test
    @DisplayName("an implausible jump is dropped instead of poisoning the totals")
    void hugeJumpIsDropped() {
        // 2 GB/s over 60 s = 120 GB budget; 500 GB in one minute is impossible on DSL.
        Delta delta = delta(Reading.of(T0, 0, 0, 500),
                Reading.of(T1, 500_000_000_000L, 10, 560));

        assertThat(delta.rxBytes()).isZero();
        assertThat(delta.dropped()).isTrue();
        assertThat(delta.txBytes())
                .as("the other direction is still counted")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("the plausibility budget grows with the gap")
    void plausibilityScalesWithElapsedTime() {
        long tenGigabytes = 10_000_000_000L;

        // 10 GB in one minute: above the 2 GB/s budget? No — 120 GB is allowed in 60 s.
        assertThat(delta(Reading.of(T0, 0, 0, 1), Reading.of(T1, tenGigabytes, 0, 61)).dropped())
                .isFalse();
        // Same 10 GB in ONE second is not plausible.
        assertThat(delta(Reading.of(T0, 0, 0, 1),
                Reading.of(T0.plusSeconds(1), 10_000_000_000_000L, 0, 2)).dropped()).isTrue();
    }

    @Test
    @DisplayName("a gap of hours keeps the whole delta; it is not spread over the gap")
    void gapIsNotSmeared() {
        Instant muchLater = T0.plus(Duration.ofHours(5));

        Delta delta = delta(Reading.of(T0, 1_000, 1_000, 500),
                Reading.of(muchLater, 6_000, 3_000, 18_500));

        assertThat(delta.rxBytes())
                .as("the caller attributes this to the hour the interval ends in")
                .isEqualTo(5_000);
        assertThat(delta.txBytes()).isEqualTo(2_000);
        assertThat(delta.dropped()).isFalse();
    }

    @Test
    @DisplayName("identical or out-of-order timestamps do not break the budget")
    void handlesZeroAndNegativeElapsed() {
        Delta same = delta(Reading.of(T0, 0, 0, 1), Reading.of(T0, 1_000, 1_000, 1));
        assertThat(same.rxBytes()).isEqualTo(1_000);
        assertThat(same.dropped()).isFalse();

        Delta backwards = delta(Reading.of(T1, 0, 0, 1), Reading.of(T0, 1_000, 1_000, 1));
        assertThat(backwards.rxBytes()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("rx and tx are decided independently")
    void directionsAreIndependent() {
        Delta delta = delta(Reading.of(T0, 1_000, 5_000, 500), Reading.of(T1, 2_000, 100, 560));

        assertThat(delta.rxBytes()).isEqualTo(1_000);
        assertThat(delta.txBytes()).isEqualTo(100);
        assertThat(delta.counterReset())
                .as("one direction resetting marks the delta as a reset")
                .isTrue();
    }

    @Test
    @DisplayName("counters above 2^31 are handled as longs")
    void handlesLargeCounters() {
        Delta delta = delta(Reading.of(T0, 3_342_582_966L, 1_503_556_195L, 331_000),
                Reading.of(T1, 3_342_583_966L, 1_503_557_195L, 331_060));

        assertThat(delta.rxBytes()).isEqualTo(1_000);
        assertThat(delta.txBytes()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("the uptime equal on both sides is not a reboot")
    void equalUptimeIsNotAReboot() {
        Delta delta = delta(Reading.of(T0, 100, 100, 500), Reading.of(T1, 200, 200, 500));

        assertThat(delta.counterReset()).isFalse();
        assertThat(delta.rxBytes()).isEqualTo(100);
    }

    @Test
    @DisplayName("the plausibility limit must be positive")
    void rejectsBadConfiguration() {
        assertThatThrownBy(() -> new UsageCalculator(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageCalculator(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rebooted() is exposed for the poller's own bookkeeping")
    void rebootedHelper() {
        assertThat(UsageCalculator.rebooted(Reading.of(T0, 0, 0, 100), Reading.of(T1, 0, 0, 10)))
                .isTrue();
        assertThat(UsageCalculator.rebooted(Reading.of(T0, 0, 0, 10), Reading.of(T1, 0, 0, 100)))
                .isFalse();
        assertThat(UsageCalculator.rebooted(new Reading(T0, 0, 0, null), Reading.of(T1, 0, 0, 100)))
                .isFalse();
    }

    private Delta delta(Reading previous, Reading current) {
        Optional<Delta> delta = calculator.delta(previous, current);
        assertThat(delta).isPresent();
        return delta.orElseThrow();
    }
}
