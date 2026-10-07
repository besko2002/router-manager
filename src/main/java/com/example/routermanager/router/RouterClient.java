package com.example.routermanager.router;

import java.time.Instant;

/**
 * The single seam between this application and the router's web UI.
 *
 * <p>Everything above this interface (poller, usage, API) is firmware-agnostic; a firmware update
 * that renames a page only touches {@link ZteWebClient}.
 */
public interface RouterClient {

    /**
     * Logs in if needed, reads one complete {@link RouterSnapshot}, and keeps the session for the
     * next call.
     *
     * @throws RouterAuthException the router refused or locked the login — do not retry
     * @throws RouterBackoffException the client is inside its back-off window and sent nothing
     * @throws RouterUnreachableException network/TLS failure
     * @throws RouterProtocolException the reply could not be understood
     */
    RouterSnapshot read();

    /** Current client state, for {@code GET /api/status}. */
    RouterClientState state();

    /** Last error message, or {@code ""}. Never contains the password. */
    String lastError();

    /** When the last {@link #read()} succeeded, or null. */
    Instant lastSuccessAt();

    /** How many login attempts this client has sent since it was created. */
    int loginAttempts();

    /**
     * Clears {@link RouterClientState#AUTH_FAILED}/{@link RouterClientState#LOCKED} after a human
     * fixed the credentials or waited out a router lock.
     */
    void resetAuth();

    /** Logs out (best effort) and releases the session. */
    void close();
}
