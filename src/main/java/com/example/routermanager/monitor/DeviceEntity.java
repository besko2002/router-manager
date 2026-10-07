package com.example.routermanager.monitor;

import com.example.routermanager.router.DeviceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Current state of one device, keyed by its normalised MAC. */
@Entity
@Table(name = "devices")
@Getter
@Setter
public class DeviceEntity {

    @Id
    @Column(name = "mac", length = 32, nullable = false)
    private String mac;

    @Column(name = "name", length = 128)
    private String name;

    /** Not populated yet: the router does not report a vendor and there is no OUI database here. */
    @Column(name = "vendor", length = 128)
    private String vendor;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 8, nullable = false)
    private DeviceKind kind;

    @Column(name = "ssid", length = 64)
    private String ssid;

    @Column(name = "port", length = 16)
    private String port;

    @Column(name = "last_ip", length = 64)
    private String lastIp;

    @Column(name = "last_rssi")
    private Integer lastRssi;

    @Column(name = "link_tx_kbps")
    private Integer linkTxKbps;

    @Column(name = "link_rx_kbps")
    private Integer linkRxKbps;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Column(name = "online", nullable = false)
    private boolean online;

    @Column(name = "alias", length = 60)
    private String alias;

    @Column(name = "trusted", nullable = false)
    private boolean trusted;

    @Column(name = "trusted_at")
    private Instant trustedAt;

    /** Consecutive polls this device was missing from; it goes offline at 2. */
    @Column(name = "missed_polls", nullable = false)
    private int missedPolls;
}
