package com.example.routermanager.router;

/** The router answered something we cannot make sense of (malformed XML, missing data). */
public class RouterProtocolException extends RouterException {

    public RouterProtocolException(String message) {
        super(message);
    }

    public RouterProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
