package com.example.routermanager.router;

/** Network-level failure (connect refused, timeout, TLS). Retried with exponential back-off. */
public class RouterUnreachableException extends RouterException {

    public RouterUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }

    public RouterUnreachableException(String message) {
        super(message);
    }
}
