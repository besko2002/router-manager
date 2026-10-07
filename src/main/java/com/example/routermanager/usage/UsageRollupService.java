package com.example.routermanager.usage;

import com.example.routermanager.monitor.CounterSampleEntity;
import com.example.routermanager.monitor.CounterSampleRepository;
import com.example.routermanager.monitor.CounterSourceType;
import com.example.routermanager.usage.UsageCalculator.Delta;
import com.example.routermanager.usage.UsageCalculator.Reading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the hourly and daily rollups from the raw counter readings.
 *
 * <p>Idempotent by construction: a bucket is always RECOMPUTED from the readings and written with
 * {@code on conflict … do update}, never incremented. Running the same window twice therefore gives
 * the same numbers.
 *
 * <p>Two rules make partial windows safe:
 * <ul>
 *   <li>the requested window is widened to the start of its first local hour, so an hour is never
 *       written from half of its readings;</li>
 *   <li>daily buckets are summed from {@code usage_hourly} (all 24 hours of the day), never from the
 *       deltas of the current window — otherwise a one-minute rollup would overwrite a whole day
 *       with one minute of traffic.</li>
 * </ul>
 *
 * <p>Buckets are never deleted: retention removes raw samples after 14 days, and recomputing an old
 * window must not erase a rollup whose readings are already gone.
 */
@Service
public class UsageRollupService {

    private static final Logger log = LoggerFactory.getLogger(UsageRollupService.class);

    private static final String UPSERT_HOURLY = """
            insert into usage_hourly (hour_start, source_type, source_id, rx_bytes, tx_bytes)
            values (?, ?, ?, ?, ?)
            on conflict (hour_start, source_type, source_id)
            do update set rx_bytes = excluded.rx_bytes, tx_bytes = excluded.tx_bytes
            """;

    private static final String UPSERT_DAILY = """
            insert into usage_daily (day_start, source_type, source_id, rx_bytes, tx_bytes)
            values (?, ?, ?, ?, ?)
            on conflict (day_start, source_type, source_id)
            do update set rx_bytes = excluded.rx_bytes, tx_bytes = excluded.tx_bytes
            """;

    private final CounterSampleRepository samples;
    private final UsageHourlyRepository hourlyRepository;
    private final JdbcTemplate jdbc;
    private final UsageProperties properties;
    private final UsageCalculator calculator;

    public UsageRollupService(CounterSampleRepository samples,
                              UsageHourlyRepository hourlyRepository,
                              JdbcTemplate jdbc,
                              UsageProperties properties) {
        this.samples = samples;
        this.hourlyRepository = hourlyRepository;
        this.jdbc = jdbc;
        this.properties = properties;
        this.calculator = new UsageCalculator(properties.getMaxPlausibleBytesPerSecond());
    }

    /** What one rollup run did; useful in tests and logs. */
    public record RollupResult(int hourlyBuckets, int dailyBuckets, int deltas, int resets,
                               int dropped) {

        public static RollupResult empty() {
            return new RollupResult(0, 0, 0, 0, 0);
        }
    }

    private record SourceKey(CounterSourceType type, String id) {
    }

    private record BucketKey(Instant hourStart, SourceKey source) {
    }

    /**
     * Recomputes every hourly bucket touched by the readings in {@code [from, to]} and every daily
     * bucket those hours belong to.
     */
    @Transactional
    public RollupResult rollUp(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            return RollupResult.empty();
        }
        ZoneId zone = properties.getZone();
        Instant windowStart = hourStart(from, zone);

        List<CounterSampleEntity> window = samples.findWindow(windowStart, to);
        if (window.isEmpty()) {
            return RollupResult.empty();
        }

        Map<SourceKey, List<CounterSampleEntity>> bySource = new LinkedHashMap<>();
        for (CounterSampleEntity sample : window) {
            bySource.computeIfAbsent(new SourceKey(sample.getSourceType(), sample.getSourceId()),
                    key -> new ArrayList<>()).add(sample);
        }

        Map<BucketKey, long[]> hourlyTotals = new LinkedHashMap<>();
        int deltas = 0;
        int resets = 0;
        int dropped = 0;

        for (Map.Entry<SourceKey, List<CounterSampleEntity>> entry : bySource.entrySet()) {
            SourceKey source = entry.getKey();
            List<CounterSampleEntity> readings = entry.getValue();

            // The anchor is the last reading BEFORE the window: without it the first interval of
            // the window would silently disappear.
            Optional<CounterSampleEntity> anchor =
                    samples.findLatestBefore(source.type(), source.id(), windowStart);
            Reading previous = anchor.map(UsageRollupService::toReading).orElse(null);

            // Every hour that has readings is rewritten, even when its traffic is zero.
            for (CounterSampleEntity sample : readings) {
                hourlyTotals.computeIfAbsent(
                        new BucketKey(hourStart(sample.getTakenAt(), zone), source),
                        key -> new long[2]);
            }

            for (CounterSampleEntity sample : readings) {
                Reading current = toReading(sample);
                Optional<Delta> delta = calculator.delta(previous, current);
                previous = current;
                if (delta.isEmpty()) {
                    continue;
                }
                Delta value = delta.get();
                deltas++;
                if (value.counterReset()) {
                    resets++;
                }
                if (value.dropped()) {
                    dropped++;
                    log.warn("Implausible counter jump for {} {} at {}; the delta was dropped",
                            source.type(), source.id(), sample.getTakenAt());
                }
                // The delta belongs to the hour the interval ENDS in — gaps are never smeared.
                long[] totals = hourlyTotals.computeIfAbsent(
                        new BucketKey(hourStart(sample.getTakenAt(), zone), source),
                        key -> new long[2]);
                totals[0] += value.rxBytes();
                totals[1] += value.txBytes();
            }
        }

        hourlyTotals.forEach((key, totals) -> jdbc.update(UPSERT_HOURLY,
                OffsetDateTime.ofInstant(key.hourStart(), ZoneOffset.UTC),
                key.source().type().name(),
                key.source().id(),
                totals[0],
                totals[1]));

        int dailyBuckets = rollUpDays(hourlyTotals.keySet(), zone);

        log.debug("Rollup {}..{}: {} hourly buckets, {} daily buckets, {} deltas ({} resets, {} dropped)",
                windowStart, to, hourlyTotals.size(), dailyBuckets, deltas, resets, dropped);
        return new RollupResult(hourlyTotals.size(), dailyBuckets, deltas, resets, dropped);
    }

    /**
     * Rewrites the daily buckets of every local day that the given hours fall into, by summing the
     * hourly table — so a partial window can never truncate a day.
     */
    private int rollUpDays(Set<BucketKey> touched, ZoneId zone) {
        if (touched.isEmpty()) {
            return 0;
        }
        Set<LocalDate> days = new TreeSet<>();
        touched.forEach(key -> days.add(LocalDate.ofInstant(key.hourStart(), zone)));

        Set<SourceKey> sourcesTouched = new LinkedHashSet<>();
        touched.forEach(key -> sourcesTouched.add(key.source()));

        int written = 0;
        for (LocalDate day : days) {
            Instant dayStart = day.atStartOfDay(zone).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();

            Map<SourceKey, long[]> totals = new LinkedHashMap<>();
            sourcesTouched.forEach(source -> totals.put(source, new long[2]));
            for (UsageHourlyEntity bucket : hourlyRepository.findWindow(dayStart, dayEnd)) {
                SourceKey source = new SourceKey(bucket.getSourceType(), bucket.getSourceId());
                long[] sums = totals.computeIfAbsent(source, key -> new long[2]);
                sums[0] += bucket.getRxBytes();
                sums[1] += bucket.getTxBytes();
            }
            for (Map.Entry<SourceKey, long[]> entry : totals.entrySet()) {
                jdbc.update(UPSERT_DAILY, day, entry.getKey().type().name(), entry.getKey().id(),
                        entry.getValue()[0], entry.getValue()[1]);
                written++;
            }
        }
        return written;
    }

    private static Reading toReading(CounterSampleEntity sample) {
        return new Reading(sample.getTakenAt(), sample.getRxBytes(), sample.getTxBytes(),
                sample.getRouterUptimeS());
    }

    /** The instant the LOCAL hour containing {@code instant} starts at. */
    public static Instant hourStart(Instant instant, ZoneId zone) {
        return instant.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant();
    }
}
