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

/** One change in a device's life. Never one row per poll. */
@Entity
@Table(name = "device_events")
@Getter
@Setter
public class DeviceEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "mac", length = 32, nullable = false)
    private String mac;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16, nullable = false)
    private DeviceEventType type;

    @Column(name = "details", length = 512)
    private String details;

    public static DeviceEventEntity of(String mac, Instant at, DeviceEventType type, String details) {
        DeviceEventEntity event = new DeviceEventEntity();
        event.mac = mac;
        event.at = at;
        event.type = type;
        event.details = details;
        return event;
    }
}
