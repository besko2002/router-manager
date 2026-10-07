package com.example.routermanager.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/** Signed, expiring owner tokens; only the server secret can authorize API requests. */
@Component
public class AppJwt {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] secret;
    private final Duration expiration;
    private final Clock clock;
    private final ObjectMapper mapper;

    public AppJwt(@Value("${app.jwt.secret}") String secret,
                  @Value("${app.jwt.expiration:PT12H}") Duration expiration,
                  Clock clock, ObjectMapper mapper) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) throw new IllegalArgumentException("APP_JWT_SECRET must be at least 32 bytes");
        if (expiration.isNegative() || expiration.isZero()) throw new IllegalArgumentException("APP_JWT_EXPIRATION must be positive");
        this.expiration = expiration;
        this.clock = clock;
        this.mapper = mapper;
    }

    public String issue(AppUser user) {
        try {
            String header = encode(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String payload = encode(mapper.writeValueAsBytes(Map.of("sub", user.getUsername(),
                    "exp", clock.instant().plus(expiration).getEpochSecond())));
            String content = header + "." + payload;
            return content + "." + encode(sign(content));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not issue token", ex);
        }
    }

    public Optional<String> verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) return Optional.empty();
            String content = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(sign(content), DECODER.decode(parts[2]))) return Optional.empty();
            JsonNode header = mapper.readTree(DECODER.decode(parts[0]));
            JsonNode payload = mapper.readTree(DECODER.decode(parts[1]));
            if (!"HS256".equals(header.path("alg").asText()) || !payload.path("exp").canConvertToLong()
                    || clock.instant().getEpochSecond() >= payload.path("exp").asLong()) return Optional.empty();
            String username = payload.path("sub").asText("");
            return username.isBlank() ? Optional.empty() : Optional.of(username);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private byte[] sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
    }
    private static String encode(byte[] bytes) { return ENCODER.encodeToString(bytes); }
}
