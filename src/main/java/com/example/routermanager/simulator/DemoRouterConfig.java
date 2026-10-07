package com.example.routermanager.simulator;

import com.example.routermanager.router.RouterClient;
import com.example.routermanager.router.RouterProperties;
import com.example.routermanager.router.ZteWebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Duration;

/**
 * Profile {@code demo}: run the whole application against the embedded simulator, so the dashboard
 * shows live, moving data without any real router — and without ever risking a login lock.
 */
@Configuration
@Profile("demo")
public class DemoRouterConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoRouterConfig.class);

    static final String DEMO_USERNAME = "demo";
    static final String DEMO_PASSWORD = "demo-password";

    @Bean
    public Clock routerClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public RouterSimulator routerSimulator() {
        RouterSimulator simulator = new RouterSimulator(DEMO_USERNAME, DEMO_PASSWORD, 3,
                Duration.ofSeconds(60), Duration.ofMinutes(30), Clock.systemUTC()).start();
        log.info("Demo profile: simulated router at {}", simulator.baseUrl());
        return simulator;
    }

    @Bean(destroyMethod = "close")
    public RouterClient routerClient(RouterSimulator simulator, RouterProperties properties, Clock clock) {
        RouterProperties demo = new RouterProperties();
        demo.setUrl(simulator.baseUrl());
        demo.setUsername(DEMO_USERNAME);
        demo.setPassword(DEMO_PASSWORD);
        demo.setInsecureTls(false);
        demo.setRequestTimeout(properties.getRequestTimeout());
        demo.setMinReloginInterval(properties.getMinReloginInterval());
        demo.setInitialBackoff(properties.getInitialBackoff());
        demo.setMaxBackoff(properties.getMaxBackoff());
        demo.setPollInterval(properties.getPollInterval());
        return new ZteWebClient(demo, clock);
    }

    /** Keeps the simulated household busy so charts and device lists move. */
    @Bean
    public DemoTrafficGenerator demoTrafficGenerator(RouterSimulator simulator) {
        return new DemoTrafficGenerator(simulator);
    }
}
