package com.example.routermanager.monitor;

import com.example.routermanager.usage.UsageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;

@Component
public class SampleRetention {
    private static final Logger log = LoggerFactory.getLogger(SampleRetention.class);
    private final CounterSampleRepository samples;
    private final UsageProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SampleRetention(CounterSampleRepository samples, UsageProperties properties, Clock clock,
                           PlatformTransactionManager manager) {
        this.samples = samples;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(cron = "${usage.retention-cron:0 20 3 * * *}", zone = "${usage.zone:Africa/Cairo}")
    public void scheduledRetention() {
        try {
            transaction.executeWithoutResult(ignored -> prune());
        } catch (Exception ex) {
            log.error("Sample retention failed", ex);
        }
    }

    @Transactional
    public int prune() {
        return samples.deleteOlderThan(clock.instant().minusSeconds(properties.getSampleRetentionDays() * 86400L));
    }
}
