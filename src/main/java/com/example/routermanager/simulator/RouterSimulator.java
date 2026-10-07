package com.example.routermanager.simulator;

import com.example.routermanager.router.ZteEndpoints;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An embedded stand-in for the ZXHN H188A web UI.
 *
 * <p>It speaks the SAME protocol as the real router — session cookie, {@code sess_token}, the
 * {@code sha256(password + token)} digest, the login lock, and the rule that a data endpoint only
 * answers after its page has been opened — and serves the sanitised recorded fixtures.
 *
 * <p>It exists so that nothing in this project has to touch the real router: the real one locks the
 * account after a few failed logins, and a test suite that retries would lock the household out.
 *
 * <p>Plain HTTP on 127.0.0.1: TLS adds nothing a test can assert and would need a keystore.
 */
public class RouterSimulator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RouterSimulator.class);

    private final SimulatorState state = new SimulatorState();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private final Random random = new Random(42);

    private final String username;
    private final String password;
    private final int maxLoginFailures;
    private final Duration lockDuration;
    private Duration idleTimeout;
    private Clock clock;

    private HttpServer server;
    private ExecutorService executor;
    private int loginAttempts;
    private int failedLogins;
    private Instant lockedUntil;

    /** Which page each data endpoint needs; mirrors the real router's menuView/menuData pairing. */
    private static final Map<String, String> PAGE_OF_ENDPOINT = Map.ofEntries(
            Map.entry("accessdev_landevs_lua", ZteEndpoints.PAGE_LOCAL_NET),
            Map.entry("accessdev_ssiddev_lua", ZteEndpoints.PAGE_LOCAL_NET),
            Map.entry("eth_lanstatus_lua", ZteEndpoints.PAGE_LOCAL_NET),
            Map.entry("wlan_status_lua", ZteEndpoints.PAGE_LOCAL_NET),
            Map.entry("dsl_interface_status_lua", ZteEndpoints.PAGE_DSL_WAN),
            Map.entry("wan_internet_lua", ZteEndpoints.PAGE_DSL_WAN),
            Map.entry("arp_arptable_lua", ZteEndpoints.PAGE_ARP),
            Map.entry("macinfo_mactable_lua", ZteEndpoints.PAGE_MAC),
            Map.entry("devmgr_statusmgr_lua", ZteEndpoints.PAGE_STATUS_MGR));

    public RouterSimulator(String username, String password) {
        this(username, password, 3, Duration.ofSeconds(60), Duration.ofMinutes(5), Clock.systemUTC());
    }

    public RouterSimulator(String username, String password, int maxLoginFailures,
                           Duration lockDuration, Duration idleTimeout, Clock clock) {
        this.username = username;
        this.password = password;
        this.maxLoginFailures = maxLoginFailures;
        this.lockDuration = lockDuration;
        this.idleTimeout = idleTimeout;
        this.clock = clock;
    }

    private static final class Session {
        final String id;
        final Set<String> openPages = new HashSet<>();
        String sessToken = "";
        String loginToken = "";
        boolean loggedIn;
        Instant lastSeen;

        Session(String id, Instant now) {
            this.id = id;
            this.lastSeen = now;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized RouterSimulator start() {
        if (server != null) {
            return this;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the router simulator", e);
        }
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "router-simulator");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        log.info("Router simulator listening on {}", baseUrl());
        return this;
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized String baseUrl() {
        if (server == null) {
            throw new IllegalStateException("simulator not started");
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public SimulatorState state() {
        return state;
    }

    // ------------------------------------------------------------------ test hooks

    public synchronized int loginAttempts() {
        return loginAttempts;
    }

    public synchronized int failedLogins() {
        return failedLogins;
    }

    public synchronized boolean locked() {
        return lockedUntil != null && clock.instant().isBefore(lockedUntil);
    }

    public synchronized void lockNow() {
        lockedUntil = clock.instant().plus(lockDuration);
    }

    public synchronized void unlock() {
        lockedUntil = null;
        failedLogins = 0;
    }

    /** Every session forgets it was logged in: the next data call answers {@code SessionTimeout}. */
    public synchronized void expireSessions() {
        sessions.values().forEach(session -> {
            session.loggedIn = false;
            session.openPages.clear();
        });
    }

    /** Sessions stay but forget which pages are open — the other way to get a SessionTimeout. */
    public synchronized void closeAllPages() {
        sessions.values().forEach(session -> session.openPages.clear());
    }

    public synchronized void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public synchronized void setClock(Clock clock) {
        this.clock = clock;
    }

    public synchronized int sessionCount() {
        return (int) sessions.values().stream().filter(session -> session.loggedIn).count();
    }

    public synchronized List<String> requestLog() {
        return List.copyOf(requests);
    }

    public synchronized void clearRequestLog() {
        requests.clear();
    }

    /** Resets the login bookkeeping, as a reboot of the simulated router would. */
    public synchronized void resetLoginState() {
        loginAttempts = 0;
        failedLogins = 0;
        lockedUntil = null;
    }

    // ------------------------------------------------------------------ request handling

    private void handle(HttpExchange exchange) {
        try {
            URI uri = exchange.getRequestURI();
            Map<String, String> query = parseQuery(uri.getRawQuery());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseQuery(body);
            String type = query.getOrDefault("_type", "");
            String tag = query.getOrDefault("_tag", "");

            synchronized (this) {
                requests.add(exchange.getRequestMethod() + " " + (uri.getQuery() == null ? uri.getPath()
                        : "?_type=" + type + "&_tag=" + tag));
            }

            if ("loginData".equals(type)) {
                handleLoginData(exchange, tag, form);
                return;
            }
            if ("menuView".equals(type)) {
                handleMenuView(exchange, tag);
                return;
            }
            if ("menuData".equals(type)) {
                handleMenuData(exchange, tag);
                return;
            }
            handleRoot(exchange);
        } catch (Exception e) {
            log.warn("Simulator failed to handle a request: {}", e.toString());
            quietly(() -> respond(exchange, 500, "text/plain", "simulator error"));
        } finally {
            exchange.close();
        }
    }

    /** {@code GET /}: hands out the session cookie and the logout token. */
    private void handleRoot(HttpExchange exchange) throws IOException {
        Session session = sessionFor(exchange, true);
        String html = """
                <html><head><title>ZXHN H188A</title></head>
                <body><script>
                var _sessionTmpToken = "%s";
                </script></body></html>
                """.formatted(tmpToken(session));
        respond(exchange, 200, "text/html; charset=utf-8", html);
    }

    private void handleLoginData(HttpExchange exchange, String tag, Map<String, String> form)
            throws IOException {
        Session session = sessionFor(exchange, true);
        switch (tag) {
            case "login_entry" -> {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleLoginPost(exchange, session, form);
                } else {
                    handleLoginState(exchange, session);
                }
            }
            case "login_token" -> {
                String token;
                synchronized (this) {
                    token = String.valueOf(10_000_000 + random.nextInt(89_999_999));
                    session.loginToken = token;
                }
                respond(exchange, 200, "text/xml",
                        "<ajax_response_xml_root>" + token + "</ajax_response_xml_root>");
            }
            case "logout_entry" -> {
                synchronized (this) {
                    session.loggedIn = false;
                    session.openPages.clear();
                }
                respond(exchange, 200, "application/json", "{\"logout\":\"ok\"}");
            }
            default -> respond(exchange, 200, "application/json", "{}");
        }
    }

    /** The state call: the client uses it to find out whether logging in is safe at all. */
    private void handleLoginState(HttpExchange exchange, Session session) throws IOException {
        long remaining;
        String sessToken;
        String prompt;
        synchronized (this) {
            remaining = lockRemainingSeconds();
            session.sessToken = "SESS" + Math.abs(random.nextInt());
            sessToken = session.sessToken;
            prompt = remaining > 0
                    ? "You have login failed for " + failedLogins + " times continuously. " : "";
        }
        respond(exchange, 200, "application/json", """
                {"lockingTime":%d,"sess_token":"%s","promptMsg":"%s","loginErrMsg":"","loginErrType":""}"""
                .formatted(remaining, sessToken, prompt));
    }

    private void handleLoginPost(HttpExchange exchange, Session session, Map<String, String> form)
            throws IOException {
        String reply;
        synchronized (this) {
            loginAttempts++;
            long remaining = lockRemainingSeconds();
            if (remaining > 0) {
                reply = lockedReply(remaining);
            } else {
                String expected = sha256Hex(password + session.loginToken);
                boolean tokenOk = !session.sessToken.isEmpty()
                        && session.sessToken.equals(form.getOrDefault("_sessionTOKEN", ""));
                boolean userOk = username.equals(form.getOrDefault("Username", ""));
                boolean passwordOk = expected.equals(form.getOrDefault("Password", ""));
                if (tokenOk && userOk && passwordOk) {
                    session.loggedIn = true;
                    session.openPages.clear();
                    failedLogins = 0;
                    reply = """
                            {"login_need_refresh":true,"sess_token":"%s","loginErrType":""}"""
                            .formatted(session.sessToken);
                } else {
                    failedLogins++;
                    if (failedLogins >= maxLoginFailures) {
                        lockedUntil = clock.instant().plus(lockDuration);
                    }
                    // The real router answers a failed attempt with a non-zero lockingTime and a
                    // promptMsg, even before the hard lock kicks in.
                    reply = lockedReply(lockDuration.toSeconds());
                }
            }
        }
        respond(exchange, 200, "application/json", reply);
    }

    private String lockedReply(long lockingTime) {
        return """
                {"lockingTime":%d,"sess_token":"","promptMsg":"You have login failed for %d times continuously. ","loginErrMsg":"","loginErrType":""}"""
                .formatted(lockingTime, failedLogins);
    }

    private void handleMenuView(HttpExchange exchange, String page) throws IOException {
        Session session = sessionFor(exchange, false);
        if (session == null || !isLive(session)) {
            respond(exchange, 200, "text/html", "<html><body>login</body></html>");
            return;
        }
        synchronized (this) {
            session.openPages.add(page);
        }
        respond(exchange, 200, "text/html; charset=utf-8",
                "<html><body>page " + page + "</body></html>");
    }

    private void handleMenuData(HttpExchange exchange, String tag) throws IOException {
        Session session = sessionFor(exchange, false);
        String fixture = ZteEndpoints.fixtureName(tag);
        String requiredPage = PAGE_OF_ENDPOINT.get(fixture);

        boolean allowed;
        synchronized (this) {
            allowed = session != null && isLive(session) && requiredPage != null
                    && session.openPages.contains(requiredPage);
        }
        if (!allowed) {
            respond(exchange, 200, "text/xml", SimulatorState.fixtureText("session_timeout.xml"));
            return;
        }
        String payload = state.renderEndpoint(fixture);
        if (payload == null) {
            respond(exchange, 200, "text/xml", SimulatorState.fixtureText("session_timeout.xml"));
            return;
        }
        respond(exchange, 200, "text/xml", payload);
    }

    // ------------------------------------------------------------------ sessions

    private synchronized Session sessionFor(HttpExchange exchange, boolean createIfMissing) {
        String cookie = Optional.ofNullable(exchange.getRequestHeaders().getFirst("Cookie")).orElse("");
        String id = null;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("SID_HTTPS_=")) {
                id = trimmed.substring("SID_HTTPS_=".length());
            }
        }
        Session session = id == null ? null : sessions.get(id);
        if (session != null) {
            if (!isLive(session)) {
                session.loggedIn = false;
                session.openPages.clear();
            }
            session.lastSeen = clock.instant();
            return session;
        }
        if (!createIfMissing) {
            return null;
        }
        String newId = "SID" + Math.abs(random.nextLong());
        Session created = new Session(newId, clock.instant());
        sessions.put(newId, created);
        exchange.getResponseHeaders().add("Set-Cookie", "SID_HTTPS_=" + newId + "; Path=/; HttpOnly");
        return created;
    }

    private boolean isLive(Session session) {
        return session.loggedIn
                && Duration.between(session.lastSeen, clock.instant()).compareTo(idleTimeout) <= 0;
    }

    private long lockRemainingSeconds() {
        if (lockedUntil == null) {
            return 0;
        }
        Instant now = clock.instant();
        if (!now.isBefore(lockedUntil)) {
            lockedUntil = null;
            failedLogins = 0;
            return 0;
        }
        return Math.max(1, Duration.between(now, lockedUntil).toSeconds());
    }

    private String tmpToken(Session session) {
        return "TMP" + Integer.toHexString(session.id.hashCode());
    }

    // ------------------------------------------------------------------ plumbing

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            values.putIfAbsent(decode(key), decode(value));
        }
        return values;
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    static String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void quietly(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception ignored) {
            // the exchange is already broken; nothing useful left to do
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** NEVER includes the configured password. */
    @Override
    public String toString() {
        return "RouterSimulator{url=" + (server == null ? "stopped" : baseUrl())
                + ", username=" + username + ", password=***, loginAttempts=" + loginAttempts + '}';
    }
}
