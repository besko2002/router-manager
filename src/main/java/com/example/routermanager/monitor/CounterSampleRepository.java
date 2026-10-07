package com.example.routermanager.monitor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CounterSampleRepository extends JpaRepository<CounterSampleEntity, Long> {

    /** Every reading of every source inside the window, oldest first. */
    @Query("""
            select s from CounterSampleEntity s
            where s.takenAt >= :from and s.takenAt <= :to
            order by s.sourceType, s.sourceId, s.takenAt
            """)
    List<CounterSampleEntity> findWindow(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * The newest reading of one source strictly before {@code before} — the anchor a window needs
     * to compute its very first delta.
     */
    @Query("""
            select s from CounterSampleEntity s
            where s.sourceType = :sourceType and s.sourceId = :sourceId and s.takenAt < :before
            order by s.takenAt desc
            limit 1
            """)
    Optional<CounterSampleEntity> findLatestBefore(@Param("sourceType") CounterSourceType sourceType,
                                                   @Param("sourceId") String sourceId,
                                                   @Param("before") Instant before);

    @Query("select max(s.takenAt) from CounterSampleEntity s")
    Optional<Instant> findLatestTakenAt();

    long countByTakenAtGreaterThanEqual(Instant from);

    boolean existsBySourceTypeAndSourceIdAndTakenAt(CounterSourceType sourceType, String sourceId,
                                                     Instant takenAt);

    @Modifying
    @Query("delete from CounterSampleEntity s where s.takenAt < :before")
    int deleteOlderThan(@Param("before") Instant before);

    List<CounterSampleEntity> findBySourceTypeAndSourceIdOrderByTakenAtAsc(CounterSourceType sourceType,
                                                                           String sourceId);
}
