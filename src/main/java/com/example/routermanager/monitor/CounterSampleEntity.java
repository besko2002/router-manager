package com.example.routermanager.monitor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One raw reading of one cumulative router counter. Deltas are never stored here, so a wrong
 * calculation can always be redone from the readings.
 */
@Entity
@Table(name = "counter_samples")
@Getter
@Setter
public class CounterSampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 16, nullable = false)
    private CounterSourceType sourceType;

    @Column(name = "source_id", length = 32, nullable = false)
    private String sourceId;

    @Column(name = "rx_bytes", nullable = false)
    private long rxBytes;

    @Column(name = "tx_bytes", nullable = false)
    private long txBytes;

    /** WAN uptime at the moment of the reading; a drop means the counters restarted at 0. */
    @Column(name = "router_uptime_s")
    private Long routerUptimeS;

    public static CounterSampleEntity of(Instant takenAt, CounterSourceType sourceType, String sourceId,
                                         long rxBytes, long txBytes, Long routerUptimeS) {
        CounterSampleEntity sample = new CounterSampleEntity();
        sample.takenAt = takenAt;
        sample.sourceType = sourceType;
        sample.sourceId = sourceId;
        sample.rxBytes = rxBytes;
        sample.txBytes = txBytes;
        sample.routerUptimeS = routerUptimeS;
        return sample;
    }
}
