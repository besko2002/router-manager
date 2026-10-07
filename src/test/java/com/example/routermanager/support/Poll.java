package com.example.routermanager.support;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tiny polling helper, in place of an extra test dependency: waits for a condition instead of
 * sleeping a guessed amount of time.
 */
public final class Poll {

    private static final Duration STEP = Duration.ofMillis(25);

    private Poll() {
    }

    public static void until(String what, Duration timeout, BooleanSupplier condition) {
        until(what, timeout, condition, () -> "");
    }

    public static void until(String what, Duration timeout, BooleanSupplier condition,
                             Supplier<String> diagnosis) {
        Instant deadline = Instant.now().plus(timeout);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
                last = null;
            } catch (RuntimeException e) {
                last = e;
            }
            sleep();
        }
        String detail = diagnosis.get();
        fail("timed out after " + timeout + " waiting for: " + what
                + (detail.isBlank() ? "" : " — " + detail)
                + (last == null ? "" : " (last error: " + last + ")"));
    }

    private static void sleep() {
        try {
            Thread.sleep(STEP.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
