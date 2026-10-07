package com.example.routermanager.router;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Router access configuration.
 *
 * <p>The password is bound from the environment and is deliberately absent from {@link #toString()}
 * — Spring Boot prints properties in several places (actuator, failure analyzers, debug logs) and
 * the router password must never end up in any of them.
 */
@ConfigurationProperties(prefix = "router")
public class RouterProperties {

    /** Base URL of the router web UI, e.g. {@code https://192.168.1.1}. */
    private String url = "https://192.168.1.1";

    private String username = "";

    private String password = "";

    /**
     * Accept the router's self-signed certificate. Applied ONLY to the host in {@link #url}; every
     * other host keeps full JDK validation.
     */
    private boolean insecureTls = true;

    /** How often the poller reads the router. */
    private Duration pollInterval = Duration.ofSeconds(60);

    /**
     * Hard floor between two login attempts once a login has FAILED. The H188A locks the account
     * after a few bad attempts, so this is a safety device, not a tuning knob.
     */
    private Duration minReloginInterval = Duration.ofMinutes(10);

    /** Per-request connect/read timeout. */
    private Duration requestTimeout = Duration.ofSeconds(10);

    /** First back-off step after a NETWORK error; doubles up to {@link #maxBackoff}. */
    private Duration initialBackoff = Duration.ofSeconds(15);

    private Duration maxBackoff = Duration.ofMinutes(5);

    /** Master switch for the scheduled poller (tests and unit runs switch it off). */
    private boolean pollEnabled = true;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isInsecureTls() {
        return insecureTls;
    }

    public void setInsecureTls(boolean insecureTls) {
        this.insecureTls = insecureTls;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getMinReloginInterval() {
        return minReloginInterval;
    }

    public void setMinReloginInterval(Duration minReloginInterval) {
        this.minReloginInterval = minReloginInterval;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public boolean isPollEnabled() {
        return pollEnabled;
    }

    public void setPollEnabled(boolean pollEnabled) {
        this.pollEnabled = pollEnabled;
    }

    /** NEVER includes the password. */
    @Override
    public String toString() {
        return "RouterProperties{url=" + url
                + ", username=" + username
                + ", password=***"
                + ", insecureTls=" + insecureTls
                + ", pollInterval=" + pollInterval
                + ", minReloginInterval=" + minReloginInterval
                + ", requestTimeout=" + requestTimeout
                + ", pollEnabled=" + pollEnabled + '}';
    }
}
