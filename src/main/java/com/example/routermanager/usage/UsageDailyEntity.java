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

import java.time.LocalDate;

/** Traffic of one source on one LOCAL day (configured zone, default Africa/Cairo). */
@Entity
@Table(name = "usage_daily")
@Getter
@Setter
public class UsageDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "day_start", nullable = false)
    private LocalDate dayStart;

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
