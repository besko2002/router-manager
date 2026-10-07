package com.example.routermanager.monitor;

import com.example.routermanager.router.RouterClientState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** The single row that describes how the router and the poller are doing. */
@Entity
@Table(name = "router_status")
@Getter
@Setter
public class RouterStatusEntity {

    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Integer id = SINGLETON_ID;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private RouterClientState state = RouterClientState.NEW;

    @Column(name = "last_ok_at")
    private Instant lastOkAt;

    @Column(name = "last_poll_at")
    private Instant lastPollAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "firmware", length = 64)
    private String firmware;

    @Column(name = "hardware", length = 64)
    private String hardware;

    @Column(name = "uptime_s")
    private Long uptimeS;

    @Column(name = "dsl_down_current_kbps")
    private Integer dslDownCurrentKbps;

    @Column(name = "dsl_down_max_kbps")
    private Integer dslDownMaxKbps;

    @Column(name = "dsl_up_current_kbps")
    private Integer dslUpCurrentKbps;

    @Column(name = "dsl_up_max_kbps")
    private Integer dslUpMaxKbps;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.EPOCH;
}
