package com.example.routermanager.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link RouterClient} for the ZTE ZXHN H188A web UI.
 *
 * <h2>Login safety (the critical part)</h2>
 * The router locks the account after a handful of bad logins, which would lock the household out of
 * its own router. Therefore:
 * <ul>
 *   <li>one login attempt at a time, never in a loop;</li>
 *   <li>a FAILED attempt starts a hard quiet window of {@code router.min-relogin-interval}
 *       (default 10 minutes) during which no login request leaves this process;</li>
 *   <li>a credential rejection also sets {@link RouterClientState#AUTH_FAILED} and requires a human
 *       ({@code POST /api/admin/router/reset-auth} or a restart) — waiting is not enough, because
 *       waiting would just burn the next attempt on the same wrong password;</li>
 *   <li>if the pre-login state call already reports a lock, no login is attempted at all;</li>
 *   <li>network errors (no answer, TLS, timeout) use exponential back-off and never consume a login
 *       attempt budget beyond the same floor.</li>
 * </ul>
 *
 * <h2>Sessions</h2>
 * One session is reused across polls. A {@code SessionTimeout} triggers exactly ONE re-login per
 * {@link #read()}; if the retry also times out, the poll fails and the next poll tries again.
 */
public class ZteWebClient implements RouterClient {

    private static final Logger log = LoggerFactory.getLogger(ZteWebClient.class);

    private static final Pattern TMP_TOKEN =
            Pattern.compile("_sessionTmpToken\\s*=\\s*\"([^\"]*)\"");

    private final RouterProperties properties;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final RouterHttpTransport transport;

    private boolean sessionValid;
    private String sessionToken = "";
    private RouterClientState state = RouterClientState.NEW;
    private String lastError = "";
    private Instant lastSuccessAt;
    private int loginAttempts;
    private Instant lastLoginAttemptAt;
    private boolean lastLoginFailed;
    private boolean manualResetRequired;
    private int networkFailures;
    private Instant quietUntil;

    public ZteWebClient(RouterProperties properties, Clock clock) {
        this(properties, clock, new RouterHttpTransport(properties.getUrl(), properties.isInsecureTls(),
                (int) properties.getRequestTimeout().toMillis()));
    }

    ZteWebClient(RouterProperties properties, Clock clock, RouterHttpTransport transport) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    // ------------------------------------------------------------------ RouterClient

    @Override
    public synchronized RouterSnapshot read() {
        Instant now = clock.instant();
        if (manualResetRequired) {
            throw new RouterBackoffException(
                    "router credentials were rejected; fix them and POST /api/admin/router/reset-auth",
                    RouterClientState.AUTH_FAILED);
        }
        if (quietUntil != null && now.isBefore(quietUntil)) {
            throw new RouterBackoffException(
                    "waiting until " + quietUntil + " before touching the router again (state " + state + ")",
                    state);
        }
        try {
            ensureSession();
            RouterSnapshot snapshot;
            try {
                snapshot = readAll();
            } catch (RouterSessionTimeoutException expired) {
                log.info("Router session expired; re-logging in once for this poll");
                sessionValid = false;
                ensureSession();
                snapshot = readAll();
            }
            state = RouterClientState.OK;
            lastError = "";
            networkFailures = 0;
            quietUntil = null;
            lastSuccessAt = snapshot.takenAt();
            return snapshot;
        } catch (RouterBackoffException e) {
            // Nothing was sent to the router; leave the state exactly as it was.
            throw e;
        } catch (RouterAuthException e) {
            onAuthFailure(e);
            throw e;
        } catch (RouterUnreachableException e) {
            onNetworkFailure(e);
            throw e;
        } catch (RouterException e) {
            // Protocol / session problems: keep the session marked dirty, surface the error, but do
            // not start a quiet window — nothing suggests the router is angry with us.
            sessionValid = false;
            lastError = e.getMessage();
            throw e;
        }
    }

    @Override
    public synchronized RouterClientState state() {
        return state;
    }

    @Override
    public synchronized String lastError() {
        return lastError;
    }

    @Override
    public synchronized Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    @Override
    public synchronized int loginAttempts() {
        return loginAttempts;
    }

    @Override
    public synchronized void resetAuth() {
        log.info("Authentication state cleared by an operator (was {})", state);
        manualResetRequired = false;
        lastLoginFailed = false;
        quietUntil = null;
        networkFailures = 0;
        lastError = "";
        state = lastSuccessAt == null ? RouterClientState.NEW : RouterClientState.OK;
    }

    @Override
    public synchronized void close() {
        if (!sessionValid) {
            return;
        }
        try {
            String page = transport.get(ZteEndpoints.ROOT).body();
            Matcher matcher = TMP_TOKEN.matcher(page);
            String token = matcher.find() ? matcher.group(1) : sessionToken;
            Map<String, String> form = new LinkedHashMap<>();
            form.put("IF_LogOff", "1");
            form.put("_sessionTOKEN", token);
            transport.postForm(ZteEndpoints.LOGOUT, form);
        } catch (RuntimeException e) {
            log.debug("Logout failed (ignored): {}", e.getMessage());
        } finally {
            sessionValid = false;
            sessionToken = "";
            transport.clearCookies();
        }
    }

    /** Visible for tests: is a session currently held? */
    synchronized boolean hasSession() {
        return sessionValid;
    }

    /** Visible for tests / status: when the client will next consider talking to the router. */
    public synchronized Instant quietUntil() {
        return quietUntil;
    }

    public synchronized boolean manualResetRequired() {
        return manualResetRequired;
    }

    // ------------------------------------------------------------------ session

    private void ensureSession() {
        if (sessionValid) {
            return;
        }
        Instant now = clock.instant();
        if (lastLoginFailed && lastLoginAttemptAt != null) {
            Instant allowedAt = lastLoginAttemptAt.plus(properties.getMinReloginInterval());
            if (now.isBefore(allowedAt)) {
                throw new RouterBackoffException(
                        "a login attempt already failed; the next one is allowed at " + allowedAt, state);
            }
        }
        login();
    }

    private void login() {
        // 1) A plain GET on / hands out the session cookie.
        transport.get(ZteEndpoints.ROOT);

        // 2) The login state tells us whether the router accepts logins at all right now.
        JsonNode loginState = readJson(transport.get(ZteEndpoints.LOGIN_STATE).body());
        long lockingTime = asLong(loginState.path("lockingTime"));
        String stateErr = loginState.path("loginErrMsg").asText("");
        if (lockingTime > 0 || !stateErr.isBlank()) {
            lastLoginFailed = true;
            lastLoginAttemptAt = clock.instant();
            throw new RouterAuthException(
                    "the router refuses logins right now (lockingTime=" + lockingTime
                            + (stateErr.isBlank() ? "" : ", err=" + stateErr) + ")", lockingTime, false);
        }
        String sessTokenFromState = loginState.path("sess_token").asText("");

        // 3) The per-attempt salt.
        String token = AjaxXml.parse(transport.get(ZteEndpoints.LOGIN_TOKEN).body()).rootText();
        if (token.isBlank()) {
            throw new RouterProtocolException("router did not return a login token");
        }

        // 4) ONE attempt. Counted before the request, so a crash mid-request still burns the budget.
        loginAttempts++;
        lastLoginAttemptAt = clock.instant();
        lastLoginFailed = true;

        Map<String, String> form = new LinkedHashMap<>();
        form.put("Username", properties.getUsername());
        form.put("Password", sha256Hex(properties.getPassword() + token));
        form.put("action", "login");
        form.put("_sessionTOKEN", sessTokenFromState);
        JsonNode reply = readJson(transport.postForm(ZteEndpoints.LOGIN_STATE, form).body());

        String errMsg = reply.path("loginErrMsg").asText("");
        long replyLock = asLong(reply.path("lockingTime"));
        if (!errMsg.isBlank() || replyLock > 0) {
            String prompt = reply.path("promptMsg").asText("");
            throw new RouterAuthException(
                    "the router rejected the login (lockingTime=" + replyLock
                            + (errMsg.isBlank() ? "" : ", err=" + errMsg)
                            + (prompt.isBlank() ? "" : ", prompt=" + prompt) + ")",
                    replyLock, true);
        }

        sessionToken = reply.path("sess_token").asText(sessTokenFromState);
        sessionValid = true;
        lastLoginFailed = false;
        log.info("Logged in to the router (attempt #{})", loginAttempts);
    }

    private void onAuthFailure(RouterAuthException e) {
        sessionValid = false;
        lastError = e.getMessage();
        state = e.credentialFailure() ? RouterClientState.AUTH_FAILED
                : (e.locked() ? RouterClientState.LOCKED : RouterClientState.AUTH_FAILED);
        manualResetRequired = e.credentialFailure();
        Duration wait = properties.getMinReloginInterval();
        if (e.lockingTime() > 0) {
            Duration routerLock = Duration.ofSeconds(e.lockingTime());
            wait = routerLock.compareTo(wait) > 0 ? routerLock : wait;
        }
        quietUntil = clock.instant().plus(wait);
        log.error("Router login failed ({}). No further attempt before {}. {}", state, quietUntil,
                manualResetRequired ? "A human must clear this state." : "");
    }

    private void onNetworkFailure(RouterUnreachableException e) {
        sessionValid = false;
        lastError = e.getMessage();
        state = RouterClientState.UNREACHABLE;
        networkFailures++;
        Duration backoff = properties.getInitialBackoff();
        for (int i = 1; i < networkFailures && backoff.compareTo(properties.getMaxBackoff()) < 0; i++) {
            backoff = backoff.multipliedBy(2);
        }
        if (backoff.compareTo(properties.getMaxBackoff()) > 0) {
            backoff = properties.getMaxBackoff();
        }
        quietUntil = clock.instant().plus(backoff);
        log.warn("Router unreachable ({} in a row); next attempt after {}: {}", networkFailures, backoff,
                e.getMessage());
    }

    // ------------------------------------------------------------------ reading

    private RouterSnapshot readAll() {
        AjaxDocument wired = page(ZteEndpoints.PAGE_LOCAL_NET, ZteEndpoints.WIRED_DEVICES, null);
        AjaxDocument wifi = data(ZteEndpoints.WIFI_DEVICES, null);
        AjaxDocument lan = data(ZteEndpoints.LAN_STATUS, null);
        AjaxDocument wlan = data(ZteEndpoints.WLAN_STATUS, null);

        AjaxDocument dsl = page(ZteEndpoints.PAGE_DSL_WAN, ZteEndpoints.DSL_STATUS, null);
        AjaxDocument wan = data(ZteEndpoints.WAN_INTERNET, ZteEndpoints.WAN_INTERNET_EXTRA);

        AjaxDocument arp = page(ZteEndpoints.PAGE_ARP, ZteEndpoints.ARP_TABLE, null);
        AjaxDocument mac = page(ZteEndpoints.PAGE_MAC, ZteEndpoints.MAC_TABLE, null);
        AjaxDocument status = page(ZteEndpoints.PAGE_STATUS_MGR, ZteEndpoints.STATUS_MGR, null);

        RouterInfo info = ZteResponseParser.mergeWanInfo(ZteResponseParser.parseDeviceInfo(status), wan);
        List<RouterDevice> devices = ZteResponseParser.mergeDevices(
                ZteResponseParser.parseWifiDevices(wifi),
                ZteResponseParser.parseWiredDevices(wired),
                ZteResponseParser.parseArpTable(arp),
                ZteResponseParser.parseMacTable(mac));

        return new RouterSnapshot(
                info,
                devices,
                ZteResponseParser.parseSsidCounters(wlan),
                ZteResponseParser.parsePortCounters(lan),
                ZteResponseParser.parseDslRates(dsl),
                clock.instant());
    }

    /** Opens {@code page} and then reads {@code endpoint} — the order the router insists on. */
    private AjaxDocument page(String page, String endpoint, String extra) {
        transport.get(ZteEndpoints.menuView(page));
        return data(endpoint, extra);
    }

    private AjaxDocument data(String endpoint, String extra) {
        RouterHttpTransport.Response response =
                transport.get(ZteEndpoints.menuData(endpoint, extra, clock.millis()));
        if (!response.ok()) {
            throw new RouterProtocolException("HTTP " + response.status() + " for " + endpoint);
        }
        AjaxDocument document = AjaxXml.parse(response.body());
        if (document.isSessionTimeout()) {
            throw new RouterSessionTimeoutException("SessionTimeout for " + endpoint);
        }
        return document;
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode readJson(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new RouterProtocolException("router login reply is not JSON", e);
        }
    }

    private static long asLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0L;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        Long parsed = Numbers.parseLongOrNull(node.asText(""));
        return parsed == null ? 0L : parsed;
    }

    static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JVM", e);
        }
    }

    /** NEVER includes the password or the session token. */
    @Override
    public String toString() {
        return "ZteWebClient{url=" + transport.baseUrl() + ", state=" + state
                + ", loginAttempts=" + loginAttempts + ", session=" + sessionValid + '}';
    }
}
