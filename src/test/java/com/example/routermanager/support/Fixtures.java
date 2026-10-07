package com.example.routermanager.support;

import com.example.routermanager.router.AjaxDocument;
import com.example.routermanager.router.AjaxXml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The recorded (sanitised) router replies, read from {@code classpath:/simulator/} — the same copies
 * the simulator serves, so parser tests and simulator tests cannot drift apart.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static String text(String fileName) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/simulator/" + fileName)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + fileName);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read fixture " + fileName, e);
        }
    }

    public static AjaxDocument doc(String name) {
        return AjaxXml.parse(text(name + ".xml"));
    }
}
