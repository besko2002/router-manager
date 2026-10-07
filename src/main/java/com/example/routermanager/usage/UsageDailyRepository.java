package com.example.routermanager.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface UsageDailyRepository extends JpaRepository<UsageDailyEntity, Long> {

    /** Buckets for local days in {@code [from, to]}, oldest first. */
    @Query("""
            select u from UsageDailyEntity u
            where u.dayStart >= :from and u.dayStart <= :to
            order by u.dayStart, u.sourceType, u.sourceId
            """)
    List<UsageDailyEntity> findWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
