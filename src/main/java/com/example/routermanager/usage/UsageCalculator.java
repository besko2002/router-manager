package com.example.routermanager.usage;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Turns consecutive readings of one cumulative router counter into traffic.
 *
 * <p>Pure: no clock, no database, no logging. Every awkward case the H188A produces is decided
 * here, once.
 *
 * <h2>Counter resets</h2>
 * The router's counters restart at 0 on every reboot. Two signals are used, in this order:
 * <ol>
 *   <li><b>the WAN uptime dropped</b> — conclusive evidence of a reboot. The delta is then the new
 *       value itself: that is exactly the traffic since the reboot.</li>
 *   <li><b>the value dropped</b> while the uptime did not (or is unknown) — the counter was
 *       cleared without a visible reboot (the H188A also does this when an interface is
 *       reconfigured). Again the delta is the new value.</li>
 * </ol>
 * A delta is therefore never negative and never the absurd {@code previous - current} figure.
 *
 * <h2>32-bit wrap</h2>
 * Some firmware exposes these counters as 32-bit. A drop is read as a wrap at 2^32 <b>only</b> when
 * the previous value was already close to 2^32 (within {@value #WRAP_MARGIN} bytes) <b>and</b> the
 * new value is small. Rationale: a wrap can only happen from just below the ceiling, so requiring
 * both ends makes a genuine reset ("the counter went from 3 GB to 12 MB") impossible to mistake for
 * a wrap. The observed H188A values exceed 2^31 but not 2^32, which is why this is a guarded
 * special case and not the default interpretation.
 *
 * <h2>Gaps</h2>
 * A missed poll is NOT smeared backwards over the hours it spans. The whole delta is attributed to
 * the hour the interval ENDS in (the caller buckets by {@code current.takenAt}), because that is
 * the only instant we actually know something about. The alternative — spreading traffic evenly —
 * would invent data.
 *
 * <h2>Implausible jumps</h2>
 * A delta that exceeds {@code maxBytesPerSecond} times the elapsed time is dropped (counted as 0
 * and flagged), rather than poisoning a day's total with a garbage reading.
 */
public class UsageCalculator {

    /** 2^32: where a 32-bit counter rolls over. */
    public static final long WRAP_AT = 1L << 32;

    /** How close to 2^32 the previous value must be for a drop to count as a wrap (256 MiB). */
    public static final long WRAP_MARGIN = 1L << 28;

    private final long maxBytesPerSecond;

    public UsageCalculator(long maxBytesPerSecond) {
        if (maxBytesPerSecond <= 0) {
            throw new IllegalArgumentException("maxBytesPerSecond must be positive");
        }
        this.maxBytesPerSecond = maxBytesPerSecond;
    }

    /** One reading of one source. {@code routerUptimeSeconds} may be null (router did not say). */
    public record Reading(Instant takenAt, long rxBytes, long txBytes, Long routerUptimeSeconds) {

        public static Reading of(Instant takenAt, long rxBytes, long txBytes, long uptimeSeconds) {
            return new Reading(takenAt, rxBytes, txBytes, uptimeSeconds);
        }
    }

    /**
     * Traffic between two readings.
     *
     * @param counterReset the counter restarted, so the delta is "everything since the restart"
     * @param wrapped the drop was interpreted as a 32-bit roll-over
     * @param dropped at least one direction was implausible and was counted as 0
     */
    public record Delta(long rxBytes, long txBytes, boolean counterReset, boolean wrapped,
                        boolean dropped) {

        public long totalBytes() {
            return rxBytes + txBytes;
        }
    }

    /**
     * The traffic between {@code previous} and {@code current}.
     *
     * @return empty when {@code previous} is null — the first reading of a source establishes the
     *         baseline and produces no traffic
     */
    public Optional<Delta> delta(Reading previous, Reading current) {
        if (current == null) {
            return Optional.empty();
        }
        if (previous == null) {
            return Optional.empty();
        }
        long elapsedSeconds = elapsedSeconds(previous, current);
        boolean rebooted = rebooted(previous, current);

        Resolved rx = resolve(previous.rxBytes(), current.rxBytes(), rebooted, elapsedSeconds);
        Resolved tx = resolve(previous.txBytes(), current.txBytes(), rebooted, elapsedSeconds);

        return Optional.of(new Delta(
                rx.value(),
                tx.value(),
                rx.reset() || tx.reset(),
                rx.wrapped() || tx.wrapped(),
                rx.dropped() || tx.dropped()));
    }

    /** True when the router's uptime went backwards, i.e. it rebooted between the two readings. */
    public static boolean rebooted(Reading previous, Reading current) {
        Long before = previous.routerUptimeSeconds();
        Long after = current.routerUptimeSeconds();
        return before != null && after != null && after < before;
    }

    /**
     * Seconds between the readings, at least 1. Out-of-order or identical timestamps would
     * otherwise make the plausibility budget zero or negative.
     */
    private static long elapsedSeconds(Reading previous, Reading current) {
        long seconds = Duration.between(previous.takenAt(), current.takenAt()).getSeconds();
        return Math.max(seconds, 1L);
    }

    private Resolved resolve(long previous, long current, boolean rebooted, long elapsedSeconds) {
        long plausibleMax = plausibleMax(elapsedSeconds);

        if (rebooted) {
            // Everything the counter shows was produced after the reboot.
            return guard(current, plausibleMax, true, false);
        }
        if (current >= previous) {
            return guard(current - previous, plausibleMax, false, false);
        }
        if (isWrap(previous, current)) {
            return guard(WRAP_AT - previous + current, plausibleMax, false, true);
        }
        // The counter dropped without a reboot: it was cleared. Count what is there now.
        return guard(current, plausibleMax, true, false);
    }

    private static boolean isWrap(long previous, long current) {
        return previous >= WRAP_AT - WRAP_MARGIN && previous < WRAP_AT && current < WRAP_MARGIN;
    }

    private long plausibleMax(long elapsedSeconds) {
        long budget = maxBytesPerSecond * elapsedSeconds;
        return budget < 0 ? Long.MAX_VALUE : budget;
    }

    private static Resolved guard(long value, long plausibleMax, boolean reset, boolean wrapped) {
        if (value < 0) {
            return new Resolved(0, reset, wrapped, true);
        }
        if (value > plausibleMax) {
            return new Resolved(0, reset, wrapped, true);
        }
        return new Resolved(value, reset, wrapped, false);
    }

    private record Resolved(long value, boolean reset, boolean wrapped, boolean dropped) {
    }
}
