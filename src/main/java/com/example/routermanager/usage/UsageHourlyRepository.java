package com.example.routermanager.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UsageHourlyRepository extends JpaRepository<UsageHourlyEntity, Long> {

    /** Buckets whose hour STARTS inside {@code [from, to)}, oldest first. */
    @Query("""
            select u from UsageHourlyEntity u
            where u.hourStart >= :from and u.hourStart < :to
            order by u.hourStart, u.sourceType, u.sourceId
            """)
    List<UsageHourlyEntity> findWindow(@Param("from") Instant from, @Param("to") Instant to);
}
