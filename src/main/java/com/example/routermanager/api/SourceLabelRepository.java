package com.example.routermanager.api;

import com.example.routermanager.monitor.CounterSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SourceLabelRepository extends JpaRepository<SourceLabel, SourceLabel.Key> {
    Optional<SourceLabel> findBySourceTypeAndSourceId(CounterSourceType sourceType, String sourceId);
}
