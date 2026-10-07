package com.example.routermanager.usage;

import com.example.routermanager.monitor.CounterSourceType;
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

/** Traffic of one source inside one local hour. Unique per (hour, source). */
@Entity
@Table(name = "usage_hourly")
@Getter
@Setter
public class UsageHourlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** The instant the LOCAL hour starts at. */
    @Column(name = "hour_start", nullable = false)
    private Instant hourStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 16, nullable = false)
    private CounterSourceType sourceType;

    @Column(name = "source_id", length = 32, nullable = false)
    private String sourceId;

    @Column(name = "rx_bytes", nullable = false)
    private long rxBytes;

    @Column(name = "tx_bytes", nullable = false)
    private long txBytes;
}
