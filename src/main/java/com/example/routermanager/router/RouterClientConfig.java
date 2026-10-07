package com.example.routermanager.router;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/** Wires the real router client. The {@code demo} profile replaces it with a simulator-backed one. */
@Configuration
public class RouterClientConfig {

    @Bean
    @Profile("!demo")
    public Clock routerClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    @Profile("!demo")
    public RouterClient routerClient(RouterProperties properties, Clock clock) {
        return new ZteWebClient(properties, clock);
    }
}
