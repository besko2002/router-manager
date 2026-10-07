package com.example.routermanager.router;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal HTTP transport for the router web UI: one instance owns one cookie jar, i.e. one router
 * session.
 *
 * <p>Why {@link HttpURLConnection} and not {@code java.net.http.HttpClient}: the H188A serves a
 * self-signed certificate whose identity does not have to match {@code 192.168.1.1}. The JDK's
 * {@code HttpClient} hardcodes {@code endpointIdentificationAlgorithm=HTTPS} and can only be
 * relaxed through the GLOBAL system property {@code jdk.internal.httpclient.disableHostnameVerification}.
 * {@link HttpsURLConnection} takes a socket factory and a hostname verifier PER CONNECTION, so the
 * relaxation here is scoped to exactly one host — the configured router — and the JVM's default TLS
 * behaviour is never touched.
 *
 * <p>Cookies are handled explicitly instead of through {@code CookieHandler.setDefault}, which is
 * also global JVM state.
 */
public class RouterHttpTransport {

    /** One cached relaxed socket factory per JVM; it validates nothing, so it is never the default. */
    private static final ConcurrentHashMap<String, SSLSocketFactory> RELAXED_FACTORIES = new ConcurrentHashMap<>();

    private final URI baseUri;
    private final String trustedHost;
    private final boolean insecureTls;
    private final int timeoutMillis;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public RouterHttpTransport(String baseUrl, boolean insecureTls, int timeoutMillis) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.trustedHost = baseUri.getHost() == null ? "" : baseUri.getHost().toLowerCase(Locale.ROOT);
        this.insecureTls = insecureTls;
        this.timeoutMillis = timeoutMillis;
    }

    public record Response(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 400;
        }
    }

    public String baseUrl() {
        return baseUri.toString();
    }

    public Response get(String pathAndQuery) {
        return send("GET", pathAndQuery, null);
    }

    public Response postForm(String pathAndQuery, Map<String, String> form) {
        return send("POST", pathAndQuery, encodeForm(form));
    }

    public void clearCookies() {
        cookies.clear();
    }

    public Map<String, String> cookies() {
        return Map.copyOf(cookies);
    }

    /** True when the relaxed TLS configuration would apply to {@code host}. */
    public boolean trustsInsecurely(String host) {
        return insecureTls && host != null && host.toLowerCase(Locale.ROOT).equals(trustedHost);
    }

    private Response send(String method, String pathAndQuery, byte[] body) {
        URI target = URI.create(baseUri + pathAndQuery);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) target.toURL().openConnection();
            if (connection instanceof HttpsURLConnection https && trustsInsecurely(target.getHost())) {
                https.setSSLSocketFactory(relaxedSocketFactory());
                https.setHostnameVerifier(onlyTrustedHost());
            }
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("User-Agent", "router-manager/1.0");
            connection.setRequestProperty("Referer", baseUri + "/");
            String cookieHeader = cookieHeader();
            if (!cookieHeader.isEmpty()) {
                connection.setRequestProperty("Cookie", cookieHeader);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setRequestProperty("Content-Length", String.valueOf(body.length));
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }

            int status = connection.getResponseCode();
            storeCookies(connection.getHeaderFields());
            String text = readBody(connection, status);
            return new Response(status, text);
        } catch (IOException e) {
            throw new RouterUnreachableException(
                    method + " " + redact(pathAndQuery) + " failed: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            if (e instanceof RouterException routerException) {
                throw routerException;
            }
            throw new RouterUnreachableException(method + " " + redact(pathAndQuery) + " failed: " + e, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String redact(String pathAndQuery) {
        // The login POST carries the hashed password in the BODY, never in the query, but keep the
        // query short in logs anyway.
        int cut = pathAndQuery.indexOf('&');
        return cut < 0 ? pathAndQuery : pathAndQuery.substring(0, cut) + "&…";
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private void storeCookies(Map<String, List<String>> headers) {
        headers.forEach((header, values) -> {
            if (header == null || !"set-cookie".equalsIgnoreCase(header)) {
                return;
            }
            for (String value : values) {
                String pair = value.split(";", 2)[0].trim();
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    cookies.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
        });
    }

    private String cookieHeader() {
        StringBuilder out = new StringBuilder();
        cookies.forEach((name, value) -> {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(name).append('=').append(value);
        });
        return out.toString();
    }

    private static byte[] encodeForm(Map<String, String> form) {
        StringBuilder out = new StringBuilder();
        form.forEach((key, value) -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        });
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private HostnameVerifier onlyTrustedHost() {
        return new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                return hostname != null && hostname.toLowerCase(Locale.ROOT).equals(trustedHost);
            }
        };
    }

    private static SSLSocketFactory relaxedSocketFactory() {
        return RELAXED_FACTORIES.computeIfAbsent("TLS", protocol -> {
            try {
                SSLContext context = SSLContext.getInstance(protocol);
                context.init(keyManagers(), new TrustManager[]{new AcceptAnyServerCertificate()},
                        new java.security.SecureRandom());
                return context.getSocketFactory();
            } catch (Exception e) {
                throw new RouterException("cannot build the router TLS context: " + e.getMessage(), e);
            }
        });
    }

    private static javax.net.ssl.KeyManager[] keyManagers() throws Exception {
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(null, null);
        return factory.getKeyManagers();
    }

    /**
     * Accepts any server certificate. Reachable ONLY through {@link #trustsInsecurely(String)}, i.e.
     * only for the configured router host; it is never installed as a JVM default.
     */
    private static final class AcceptAnyServerCertificate implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException("client certificates are not used");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Intentionally empty: the home router signs its own certificate and there is no CA to
            // check it against. The connection stays encrypted; it is simply not authenticated.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
