package com.example.routermanager.router;

/**
 * The client deliberately did nothing: it is inside its back-off / minimum re-login window, or it
 * is waiting for a human to clear an authentication failure. No request was sent to the router.
 */
public class RouterBackoffException extends RouterException {

    private final RouterClientState state;

    public RouterBackoffException(String message, RouterClientState state) {
        super(message);
        this.state = state;
    }

    public RouterClientState state() {
        return state;
    }
}
