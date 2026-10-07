package com.example.routermanager.router;

/** Base type for everything that can go wrong while talking to the router. */
public class RouterException extends RuntimeException {

    public RouterException(String message) {
        super(message);
    }

    public RouterException(String message, Throwable cause) {
        super(message, cause);
    }
}
