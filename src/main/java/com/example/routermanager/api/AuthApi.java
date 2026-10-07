package com.example.routermanager.api;

import com.example.routermanager.common.ApiError;
import com.example.routermanager.common.BadRequestException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthApi {
    private final AppUserRepository users;
    private final AppJwt jwt;
    private final LoginLimiter limiter;
    private final Clock clock;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String bootstrapUsername;
    private final String bootstrapPassword;

    public AuthApi(AppUserRepository users, AppJwt jwt, LoginLimiter limiter, Clock clock,
                   @Value("${app.admin.username:}") String bootstrapUsername,
                   @Value("${app.admin.password:}") String bootstrapPassword) {
        this.users = users;
        this.jwt = jwt;
        this.limiter = limiter;
        this.clock = clock;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
    }

    @PostConstruct
    public void bootstrap() {
        if (users.count() == 0 && !bootstrapUsername.isBlank() && !bootstrapPassword.isBlank())
            create(bootstrapUsername, bootstrapPassword);
    }

    public record Credentials(String username, String password) {}
    public record Owner(String username) {}
    public record Login(String token, String username) {}
    public record PasswordChange(String currentPassword, String newPassword) {}

    public record SetupStatus(boolean setupRequired) {}

    @GetMapping("/setup-status")
    public ResponseEntity<?> setupStatus(HttpServletRequest request) {
        ResponseEntity<?> limited = setupLimit(request);
        if (limited != null) return limited;
        return ResponseEntity.ok(new SetupStatus(users.count() == 0));
    }

    private ResponseEntity<?> setupLimit(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        long retry = limiter.setupRetryAfter(ip);
        if (retry > 0) return ResponseEntity.status(429).header("Retry-After", Long.toString(retry))
                .body(ApiError.of(429, "Too Many Requests", "Too many setup requests", request.getRequestURI()));
        limiter.setupRequest(ip);
        return null;
    }

    @PostMapping("/setup")
    public synchronized ResponseEntity<?> setup(@RequestBody Credentials credentials, HttpServletRequest request) {
        ResponseEntity<?> limited = setupLimit(request);
        if (limited != null) return limited;
        if (users.count() != 0) return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "Conflict", "Setup has already been completed", request.getRequestURI()));
        AppUser user = create(credentials.username(), credentials.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Owner(user.getUsername()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Credentials credentials, HttpServletRequest request) {
        String username = normalize(credentials.username());
        String ip = request.getRemoteAddr();
        long retry = limiter.retryAfter(username, ip);
        if (retry > 0) return ResponseEntity.status(429).header("Retry-After", Long.toString(retry))
                .body(ApiError.of(429, "Too Many Requests", "Too many login attempts", request.getRequestURI()));
        AppUser user = users.findByUsername(username).orElse(null);
        // Always run BCrypt, including for nonexistent users: no account enumeration through response or timing.
        boolean matches = encoder.matches(credentials.password() == null ? "" : credentials.password(),
                user == null ? "$2a$10$3euPcmQFCiblsZeEu5s7p.9ry.2f2BcSqJD/GJeTY.LkpFBSJ3p5a" : user.getPasswordHash());
        if (user == null || !matches) {
            limiter.fail(username, ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiError.of(401, "Unauthorized", "Invalid username or password", request.getRequestURI()));
        }
        limiter.success(username, ip);
        return ResponseEntity.ok(new Login(jwt.issue(user), user.getUsername()));
    }

    @GetMapping("/me")
    public Owner me(HttpServletRequest request) { return new Owner((String) request.getAttribute("appUsername")); }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody PasswordChange change, HttpServletRequest request) {
        AppUser user = users.findByUsername((String) request.getAttribute("appUsername")).orElseThrow();
        if (!encoder.matches(change.currentPassword() == null ? "" : change.currentPassword(), user.getPasswordHash()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiError.of(403, "Forbidden", "Invalid current password", request.getRequestURI()));
        validatePassword(change.newPassword());
        user.setPasswordHash(encoder.encode(change.newPassword()));
        users.save(user);
        return ResponseEntity.noContent().build();
    }

    private synchronized AppUser create(String username, String password) {
        String normalized = normalize(username);
        if (normalized.isBlank() || normalized.length() > 128 || normalized.chars().anyMatch(Character::isISOControl))
            throw new BadRequestException("Invalid username");
        validatePassword(password);
        return users.save(new AppUser(normalized, encoder.encode(password), clock.instant()));
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 10) throw new BadRequestException("Password must have at least 10 characters");
    }
    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
