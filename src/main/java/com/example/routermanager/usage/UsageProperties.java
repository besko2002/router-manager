package com.example.routermanager.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/** Usage accounting configuration. */
@ConfigurationProperties(prefix = "usage")
public class UsageProperties {

    /** Hours and days are cut in this zone, so "today" means today at home. */
    private ZoneId zone = ZoneId.of("Africa/Cairo");

    /** Raw counter samples older than this are deleted; the rollups are kept. */
    private int sampleRetentionDays = 14;

    private String retentionCron = "0 20 3 * * *";

    /** A delta above this many bytes per elapsed second is treated as a broken reading. */
    private long maxPlausibleBytesPerSecond = 2_000_000_000L;

    /** Hard cap on how many points one timeseries request may return. */
    private int maxTimeseriesPoints = 2000;

    public ZoneId getZone() {
        return zone;
    }

    public void setZone(ZoneId zone) {
        this.zone = zone;
    }

    public int getSampleRetentionDays() {
        return sampleRetentionDays;
    }

    public void setSampleRetentionDays(int sampleRetentionDays) {
        this.sampleRetentionDays = sampleRetentionDays;
    }

    public String getRetentionCron() {
        return retentionCron;
    }

    public void setRetentionCron(String retentionCron) {
        this.retentionCron = retentionCron;
    }

    public long getMaxPlausibleBytesPerSecond() {
        return maxPlausibleBytesPerSecond;
    }

    public void setMaxPlausibleBytesPerSecond(long maxPlausibleBytesPerSecond) {
        this.maxPlausibleBytesPerSecond = maxPlausibleBytesPerSecond;
    }

    public int getMaxTimeseriesPoints() {
        return maxTimeseriesPoints;
    }

    public void setMaxTimeseriesPoints(int maxTimeseriesPoints) {
        this.maxTimeseriesPoints = maxTimeseriesPoints;
    }

    @Override
    public String toString() {
        return "UsageProperties{zone=" + zone + ", sampleRetentionDays=" + sampleRetentionDays
                + ", maxPlausibleBytesPerSecond=" + maxPlausibleBytesPerSecond + '}';
    }
}
