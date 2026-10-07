package com.example.routermanager.router;

/**
 * The router answered {@code <IF_ERRORSTR>SessionTimeout</IF_ERRORSTR>}: the session expired, or
 * the data endpoint was called without opening its page first.
 */
public class RouterSessionTimeoutException extends RouterException {

    public RouterSessionTimeoutException(String message) {
        super(message);
    }
}
