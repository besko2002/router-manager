package com.example.routermanager.monitor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceRepository extends JpaRepository<DeviceEntity, String> {

    List<DeviceEntity> findAllByOrderByLastSeenDesc();

    List<DeviceEntity> findByOnlineOrderByLastSeenDesc(boolean online);

    long countByOnline(boolean online);
}
