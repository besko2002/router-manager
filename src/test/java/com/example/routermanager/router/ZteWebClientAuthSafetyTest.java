package com.example.routermanager.router;

import com.example.routermanager.simulator.RouterSimulator;
import com.example.routermanager.support.LogCapture;
import com.example.routermanager.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The login-safety contract. The real H188A locks the account after a few failed logins, so these
 * are the tests that keep this project from locking its owner out of their own router.
 */
class ZteWebClientAuthSafetyTest {

    private static final String USER = "admin";
    private static final String PASSWORD = "correct-horse";
    private static final String WRONG_PASSWORD = "nope";
    private static final Duration MIN_RELOGIN = Duration.ofMinutes(10);

    private RouterSimulator simulator;
    private MutableClock clock;

    @BeforeEach
    void start() {
        simulator = new RouterSimulator(USER, PASSWORD).start();
        clock = MutableClock.at("2025-01-01T10:00:00Z");
    }

    @AfterEach
    void stop() {
        simulator.close();
    }

    private RouterProperties properties(String password) {
        RouterProperties properties = new RouterProperties();
        properties.setUrl(simulator.baseUrl());
        properties.setUsername(USER);
        properties.setPassword(password);
        properties.setInsecureTls(false);
        properties.setMinReloginInterval(MIN_RELOGIN);
        properties.setRequestTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private ZteWebClient client(String password) {
        return new ZteWebClient(properties(password), clock);
    }

    @Test
    @DisplayName("a wrong password costs exactly ONE attempt and ends in AUTH_FAILED")
    void wrongPasswordIsTriedOnce() {
        ZteWebClient client = client(WRONG_PASSWORD);

        assertThatThrownBy(client::read)
                .isInstanceOf(RouterAuthException.class)
                .hasMessageContaining("rejected the login");

        assertThat(client.state()).isEqualTo(RouterClientState.AUTH_FAILED);
        assertThat(client.loginAttempts()).isEqualTo(1);
        assertThat(simulator.loginAttempts()).isEqualTo(1);
        assertThat(simulator.failedLogins()).isEqualTo(1);
        assertThat(client.manualResetRequired()).isTrue();
    }

    @Test
    @DisplayName("after a credential rejection NOTHING is sent again, not even after a long wait")
    void credentialFailureNeedsAHuman() {
        ZteWebClient client = client(WRONG_PASSWORD);
        assertThatThrownBy(client::read).isInstanceOf(RouterAuthException.class);

        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofHours(1));
            assertThatThrownBy(client::read)
                    .isInstanceOf(RouterBackoffException.class)
                    .hasMessageContaining("reset-auth");
        }

        assertThat(simulator.loginAttempts())
                .as("one attempt only; waiting must not burn further attempts on a wrong password")
                .isEqualTo(1);
        assertThat(client.state()).isEqualTo(RouterClientState.AUTH_FAILED);
    }

    @Test
    @DisplayName("ten polls in a row after a wrong password send exactly one login request")
    void repeatedPollsDoNotHammer() {
        ZteWebClient client = client(WRONG_PASSWORD);

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(client::read).isInstanceOf(RouterException.class);
            clock.advance(Duration.ofSeconds(60));
        }

        assertThat(simulator.loginAttempts()).isEqualTo(1);
        assertThat(simulator.locked()).isFalse();
    }

    @Test
    @DisplayName("resetAuth plus fixed credentials makes the client work again")
    void resetAuthRecovers() {
        RouterProperties properties = properties(WRONG_PASSWORD);
        ZteWebClient client = new ZteWebClient(properties, clock);
        assertThatThrownBy(client::read).isInstanceOf(RouterAuthException.class);

        properties.setPassword(PASSWORD);
        client.resetAuth();

        assertThat(client.state()).isEqualTo(RouterClientState.NEW);
        assertThat(client.read().devices()).hasSize(6);
        assertThat(client.state()).isEqualTo(RouterClientState.OK);
        assertThat(simulator.loginAttempts()).isEqualTo(2);
        client.close();
    }

    @Test
    @DisplayName("a router that is ALREADY locked is not even offered a login")
    void doesNotLoginWhenAlreadyLocked() {
        simulator.lockNow();
        ZteWebClient client = client(PASSWORD);

        assertThatThrownBy(client::read)
                .isInstanceOf(RouterAuthException.class)
                .hasMessageContaining("refuses logins");

        assertThat(client.state()).isEqualTo(RouterClientState.LOCKED);
        assertThat(simulator.loginAttempts())
                .as("the state call told us it is locked; posting a login would be reckless")
                .isZero();
        assertThat(client.manualResetRequired())
                .as("a lock clears by itself, so this does not need a human")
                .isFalse();
    }

    @Test
    @DisplayName("while locked, the client waits out min-relogin-interval before trying once")
    void respectsMinReloginIntervalAfterALock() {
        simulator.lockNow();
        ZteWebClient client = client(PASSWORD);
        assertThatThrownBy(client::read).isInstanceOf(RouterAuthException.class);

        clock.advance(MIN_RELOGIN.minusSeconds(1));
        assertThatThrownBy(client::read)
                .isInstanceOf(RouterBackoffException.class)
                .hasMessageContaining("before touching the router again");
        assertThat(simulator.loginAttempts()).isZero();

        simulator.unlock();
        clock.advance(Duration.ofSeconds(2));

        assertThat(client.read().devices()).hasSize(6);
        assertThat(simulator.loginAttempts()).isEqualTo(1);
        client.close();
    }

    @Test
    @DisplayName("the quiet window is at least as long as the router's own lockingTime")
    void honoursRouterLockDuration() {
        RouterProperties properties = properties(PASSWORD);
        properties.setMinReloginInterval(Duration.ofSeconds(5));
        simulator.setClock(clock);
        simulator.lockNow();
        ZteWebClient client = new ZteWebClient(properties, clock);

        assertThatThrownBy(client::read).isInstanceOf(RouterAuthException.class);

        // The simulator reports a 60 s lock, which outranks the 5 s configured floor.
        assertThat(client.quietUntil()).isEqualTo(clock.instant().plusSeconds(60));
    }

    @Test
    @DisplayName("the client never reaches the simulator's lock threshold on its own")
    void neverLocksTheRouter() {
        ZteWebClient client = client(WRONG_PASSWORD);

        for (int i = 0; i < 20; i++) {
            try {
                client.read();
            } catch (RouterException ignored) {
                // expected
            }
            clock.advance(MIN_RELOGIN.plusSeconds(1));
        }

        assertThat(simulator.failedLogins())
                .as("three failures would lock the simulated router")
                .isEqualTo(1);
        assertThat(simulator.locked()).isFalse();
    }

    @Test
    @DisplayName("a network error backs off exponentially and is not an auth failure")
    void networkErrorsBackOffExponentially() {
        RouterProperties properties = properties(PASSWORD);
        // A port nobody listens on: connection refused.
        properties.setUrl("http://127.0.0.1:1");
        properties.setInitialBackoff(Duration.ofSeconds(10));
        properties.setMaxBackoff(Duration.ofSeconds(60));
        ZteWebClient client = new ZteWebClient(properties, clock);

        assertThatThrownBy(client::read).isInstanceOf(RouterUnreachableException.class);
        assertThat(client.state()).isEqualTo(RouterClientState.UNREACHABLE);
        assertThat(client.quietUntil()).isEqualTo(clock.instant().plusSeconds(10));
        assertThat(client.manualResetRequired()).isFalse();

        assertThatThrownBy(client::read).isInstanceOf(RouterBackoffException.class);

        clock.advance(Duration.ofSeconds(11));
        assertThatThrownBy(client::read).isInstanceOf(RouterUnreachableException.class);
        assertThat(client.quietUntil()).isEqualTo(clock.instant().plusSeconds(20));

        clock.advance(Duration.ofSeconds(21));
        assertThatThrownBy(client::read).isInstanceOf(RouterUnreachableException.class);
        assertThat(client.quietUntil()).isEqualTo(clock.instant().plusSeconds(40));

        clock.advance(Duration.ofSeconds(41));
        assertThatThrownBy(client::read).isInstanceOf(RouterUnreachableException.class);
        assertThat(client.quietUntil())
                .as("capped at max-backoff")
                .isEqualTo(clock.instant().plusSeconds(60));
    }

    @Test
    @DisplayName("a successful read clears a previous network back-off")
    void successClearsBackoff() {
        RouterProperties properties = properties(PASSWORD);
        properties.setUrl("http://127.0.0.1:1");
        properties.setInitialBackoff(Duration.ofSeconds(10));
        ZteWebClient client = new ZteWebClient(properties, clock);
        assertThatThrownBy(client::read).isInstanceOf(RouterUnreachableException.class);

        properties.setUrl(simulator.baseUrl());
        clock.advance(Duration.ofSeconds(11));
        // A fresh client is needed because the transport caches the base URL; this mirrors a restart.
        ZteWebClient fixed = new ZteWebClient(properties, clock);

        assertThat(fixed.read().devices()).hasSize(6);
        assertThat(fixed.state()).isEqualTo(RouterClientState.OK);
        assertThat(fixed.quietUntil()).isNull();
        fixed.close();
    }

    @Test
    @DisplayName("the password never appears in logs, not even when the login fails")
    void passwordIsNeverLogged() {
        try (LogCapture logs = LogCapture.start()) {
            ZteWebClient client = client(WRONG_PASSWORD);
            assertThatThrownBy(client::read).isInstanceOf(RouterAuthException.class);
            clock.advance(Duration.ofMinutes(20));
            assertThatThrownBy(client::read).isInstanceOf(RouterBackoffException.class);

            String text = logs.text();
            assertThat(text).isNotEmpty();
            assertThat(text).doesNotContain(WRONG_PASSWORD);
            assertThat(text).doesNotContain(PASSWORD);
        }
    }

    @Test
    @DisplayName("the password is absent from toString of the properties, the client and the simulator")
    void passwordIsNotInToString() {
        RouterProperties properties = properties(PASSWORD);
        ZteWebClient client = new ZteWebClient(properties, clock);

        assertThat(properties.toString()).doesNotContain(PASSWORD).contains("password=***");
        assertThat(client.toString()).doesNotContain(PASSWORD).contains("state=");
        assertThat(simulator.toString()).doesNotContain(PASSWORD).contains("password=***");
    }

    @Test
    @DisplayName("the hashed password, not the password, goes on the wire")
    void onlyTheDigestIsSent() {
        ZteWebClient client = client(PASSWORD);

        client.read();

        assertThat(simulator.requestLog()).isNotEmpty();
        assertThat(simulator.requestLog().toString()).doesNotContain(PASSWORD);
        client.close();
    }

    @Test
    @DisplayName("insecure TLS applies to the configured router host only")
    void insecureTlsIsScopedToOneHost() {
        RouterHttpTransport trusting =
                new RouterHttpTransport("https://192.168.1.1", true, 1000);

        assertThat(trusting.trustsInsecurely("192.168.1.1")).isTrue();
        assertThat(trusting.trustsInsecurely("192.168.1.2")).isFalse();
        assertThat(trusting.trustsInsecurely("example.com")).isFalse();
        assertThat(trusting.trustsInsecurely(null)).isFalse();

        RouterHttpTransport strict = new RouterHttpTransport("https://192.168.1.1", false, 1000);
        assertThat(strict.trustsInsecurely("192.168.1.1"))
                .as("router.insecure-tls=false means no exception at all")
                .isFalse();
    }

    @Test
    @DisplayName("the JVM default TLS context is never replaced")
    void doesNotTouchGlobalTls() throws Exception {
        // SSLContext.getDefault() is a JVM singleton: if anything had called setDefault(), this
        // identity check would fail.
        javax.net.ssl.SSLContext before = javax.net.ssl.SSLContext.getDefault();
        RouterProperties properties = properties(PASSWORD);
        properties.setInsecureTls(true);

        ZteWebClient client = new ZteWebClient(properties, clock);
        client.read();
        client.close();

        assertThat(javax.net.ssl.SSLContext.getDefault()).isSameAs(before);
        assertThat(javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().getClass().getName())
                .as("the default hostname verifier must still be the JDK's")
                .isEqualTo("javax.net.ssl.HttpsURLConnection$DefaultHostnameVerifier");
        assertThat(java.net.CookieHandler.getDefault())
                .as("cookies are handled per client, never through the global handler")
                .isNull();
    }

    @Test
    @DisplayName("a wrong username is handled exactly like a wrong password")
    void wrongUsernameIsAlsoACredentialFailure() {
        RouterProperties properties = properties(PASSWORD);
        properties.setUsername("not-admin");
        ZteWebClient client = new ZteWebClient(properties, clock);

        assertThatThrownBy(client::read).isInstanceOf(RouterAuthException.class);

        assertThat(client.state()).isEqualTo(RouterClientState.AUTH_FAILED);
        assertThat(client.manualResetRequired()).isTrue();
        assertThat(simulator.loginAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a login attempt is counted even when the router answers nonsense")
    void protocolErrorDuringLoginStillCountsTheAttempt() {
        RouterProperties properties = properties(PASSWORD);
        ZteWebClient client = new ZteWebClient(properties, clock,
                new RouterHttpTransport(simulator.baseUrl(), false, 2000) {
                    @Override
                    public Response get(String pathAndQuery) {
                        if (pathAndQuery.contains("login_token")) {
                            return new Response(200, "<ajax_response_xml_root></ajax_response_xml_root>");
                        }
                        return super.get(pathAndQuery);
                    }
                });

        assertThatThrownBy(client::read)
                .isInstanceOf(RouterProtocolException.class)
                .hasMessageContaining("login token");

        assertThat(simulator.loginAttempts())
                .as("no login was posted, so the router saw nothing")
                .isZero();
    }
}
