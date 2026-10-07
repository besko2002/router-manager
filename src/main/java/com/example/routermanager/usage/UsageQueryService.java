package com.example.routermanager.usage;

import com.example.routermanager.common.BadRequestException;
import com.example.routermanager.monitor.CounterSourceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the rollups back out: totals for a window and timeseries for charts. */
@Service
public class UsageQueryService {

    /** Hour buckets are capped at ~5 weeks; beyond that, ask for days. */
    public static final Duration MAX_HOUR_RANGE = Duration.ofDays(35);

    /** Day buckets are capped at ~3 years. */
    public static final Duration MAX_DAY_RANGE = Duration.ofDays(1100);

    private final UsageHourlyRepository hourly;
    private final UsageDailyRepository daily;
    private final UsageProperties properties;

    public UsageQueryService(UsageHourlyRepository hourly, UsageDailyRepository daily,
                             UsageProperties properties) {
        this.hourly = hourly;
        this.daily = daily;
        this.properties = properties;
    }

    public enum Granularity {
        HOUR,
        DAY
    }

    public enum GroupBy {
        TOTAL,
        SSID,
        PORT
    }

    /** Traffic of one counter source over the requested window. */
    public record SourceUsage(CounterSourceType sourceType, String sourceId, long rxBytes,
                              long txBytes) {

        public long totalBytes() {
            return rxBytes + txBytes;
        }
    }

    /**
     * @param from the window actually used, widened to the start of its local hour
     * @param approximate always true — see {@link UsageNotes}
     */
    public record Summary(Instant from, Instant to, long rxBytes, long txBytes,
                          List<SourceUsage> bySsid, List<SourceUsage> byPort, boolean approximate) {

        public long totalBytes() {
            return rxBytes + txBytes;
        }
    }

    public record Point(String bucket, long rxBytes, long txBytes) {

        public long totalBytes() {
            return rxBytes + txBytes;
        }
    }

    public record Series(String sourceType, String sourceId, List<Point> points) {
    }

    public record Timeseries(Granularity granularity, GroupBy groupBy, Instant from, Instant to,
                             List<Series> series, boolean approximate) {
    }

    // ------------------------------------------------------------------ summary

    /**
     * Totals per SSID and per LAN port plus the household total.
     *
     * <p>The window is widened to whole local hours, because hours are the finest bucket that
     * exists. The widened window is echoed back.
     */
    @Transactional(readOnly = true)
    public Summary summary(Instant from, Instant to) {
        validateRange(from, to, MAX_DAY_RANGE);
        ZoneId zone = properties.getZone();
        Instant alignedFrom = UsageRollupService.hourStart(from, zone);
        Instant alignedTo = alignEndOfHour(to, zone);

        Map<Key, long[]> totals = new LinkedHashMap<>();
        for (UsageHourlyEntity bucket : hourly.findWindow(alignedFrom, alignedTo)) {
            long[] sums = totals.computeIfAbsent(
                    new Key(bucket.getSourceType(), bucket.getSourceId()), key -> new long[2]);
            sums[0] += bucket.getRxBytes();
            sums[1] += bucket.getTxBytes();
        }

        List<SourceUsage> bySsid = new ArrayList<>();
        List<SourceUsage> byPort = new ArrayList<>();
        long rxTotal = 0;
        long txTotal = 0;
        for (Map.Entry<Key, long[]> entry : totals.entrySet()) {
            SourceUsage usage = new SourceUsage(entry.getKey().type(), entry.getKey().id(),
                    entry.getValue()[0], entry.getValue()[1]);
            if (usage.sourceType() == CounterSourceType.SSID) {
                bySsid.add(usage);
            } else {
                byPort.add(usage);
            }
            // The household total deliberately adds SSIDs and LAN ports together; see UsageNotes.
            rxTotal += usage.rxBytes();
            txTotal += usage.txBytes();
        }
        bySsid.sort(Comparator.comparing(SourceUsage::sourceId));
        byPort.sort(Comparator.comparing(SourceUsage::sourceId));

        return new Summary(alignedFrom, alignedTo, rxTotal, txTotal, bySsid, byPort, true);
    }

    // ------------------------------------------------------------------ timeseries

    @Transactional(readOnly = true)
    public Timeseries timeseries(Granularity granularity, GroupBy groupBy, Instant from, Instant to) {
        validateRange(from, to, granularity == Granularity.HOUR ? MAX_HOUR_RANGE : MAX_DAY_RANGE);
        ZoneId zone = properties.getZone();

        List<Bucket> buckets = granularity == Granularity.HOUR
                ? hourBuckets(from, to, zone)
                : dayBuckets(from, to, zone);

        List<Series> series = group(buckets, groupBy);
        int points = series.stream().mapToInt(one -> one.points().size()).sum();
        if (points > properties.getMaxTimeseriesPoints()) {
            throw new BadRequestException("the requested range produces " + points
                    + " points, which exceeds the limit of " + properties.getMaxTimeseriesPoints()
                    + "; narrow the range or use granularity=day");
        }
        return new Timeseries(granularity, groupBy, from, to, series, true);
    }

    private List<Bucket> hourBuckets(Instant from, Instant to, ZoneId zone) {
        Instant alignedFrom = UsageRollupService.hourStart(from, zone);
        Instant alignedTo = alignEndOfHour(to, zone);
        List<Bucket> buckets = new ArrayList<>();
        for (UsageHourlyEntity row : hourly.findWindow(alignedFrom, alignedTo)) {
            buckets.add(new Bucket(row.getHourStart().toString(), row.getSourceType(),
                    row.getSourceId(), row.getRxBytes(), row.getTxBytes()));
        }
        return buckets;
    }

    private List<Bucket> dayBuckets(Instant from, Instant to, ZoneId zone) {
        LocalDate first = LocalDate.ofInstant(from, zone);
        LocalDate last = LocalDate.ofInstant(to, zone);
        List<Bucket> buckets = new ArrayList<>();
        for (UsageDailyEntity row : daily.findWindow(first, last)) {
            buckets.add(new Bucket(row.getDayStart().toString(), row.getSourceType(),
                    row.getSourceId(), row.getRxBytes(), row.getTxBytes()));
        }
        return buckets;
    }

    private static List<Series> group(List<Bucket> buckets, GroupBy groupBy) {
        Map<String, Map<String, long[]>> bySeries = new LinkedHashMap<>();
        Map<String, String> seriesType = new LinkedHashMap<>();

        for (Bucket bucket : buckets) {
            if (groupBy == GroupBy.SSID && bucket.sourceType() != CounterSourceType.SSID) {
                continue;
            }
            if (groupBy == GroupBy.PORT && bucket.sourceType() != CounterSourceType.LAN_PORT) {
                continue;
            }
            String seriesId = groupBy == GroupBy.TOTAL ? "total" : bucket.sourceId();
            seriesType.putIfAbsent(seriesId,
                    groupBy == GroupBy.TOTAL ? "TOTAL" : bucket.sourceType().name());
            long[] sums = bySeries.computeIfAbsent(seriesId, key -> new LinkedHashMap<>())
                    .computeIfAbsent(bucket.bucket(), key -> new long[2]);
            sums[0] += bucket.rxBytes();
            sums[1] += bucket.txBytes();
        }

        List<Series> series = new ArrayList<>();
        bySeries.forEach((seriesId, points) -> {
            List<Point> list = new ArrayList<>();
            points.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> list.add(new Point(entry.getKey(), entry.getValue()[0],
                            entry.getValue()[1])));
            series.add(new Series(seriesType.get(seriesId), seriesId, list));
        });
        series.sort(Comparator.comparing(Series::sourceId));
        return series;
    }

    // ------------------------------------------------------------------ helpers

    private static void validateRange(Instant from, Instant to, Duration maxSpan) {
        if (from == null || to == null) {
            throw new BadRequestException("both 'from' and 'to' are required");
        }
        if (!from.isBefore(to)) {
            throw new BadRequestException("'from' must be before 'to'");
        }
        Duration span = Duration.between(from, to);
        if (span.compareTo(maxSpan) > 0) {
            throw new BadRequestException("the range of " + span.toDays()
                    + " days exceeds the maximum of " + maxSpan.toDays() + " days for this granularity");
        }
    }

    /** The end of the last local hour that {@code to} touches, so a partial hour is included. */
    private static Instant alignEndOfHour(Instant to, ZoneId zone) {
        Instant start = UsageRollupService.hourStart(to, zone);
        return start.equals(to) ? to : start.plus(Duration.ofHours(1));
    }

    private record Key(CounterSourceType type, String id) {
    }

    private record Bucket(String bucket, CounterSourceType sourceType, String sourceId, long rxBytes,
                          long txBytes) {
    }
}
