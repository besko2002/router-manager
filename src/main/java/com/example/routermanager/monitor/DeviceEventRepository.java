package com.example.routermanager.monitor;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface DeviceEventRepository extends JpaRepository<DeviceEventEntity, Long> {

    List<DeviceEventEntity> findByMacOrderByAtDesc(String mac, Limit limit);

    List<DeviceEventEntity> findByMacAndAtGreaterThanEqualOrderByAtDesc(String mac, Instant from,
                                                                        Limit limit);

    List<DeviceEventEntity> findByMacOrderByAtAsc(String mac);

    long countByMacAndType(String mac, DeviceEventType type);
}
