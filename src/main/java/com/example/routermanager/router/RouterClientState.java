package com.example.routermanager.router;

/** Lifecycle of the router client, surfaced through {@code GET /api/status}. */
public enum RouterClientState {

    /** No successful read yet, nothing wrong either. */
    NEW,

    /** The last read succeeded. */
    OK,

    /**
     * The router rejected our credentials. A human must fix them and clear the state through
     * {@code POST /api/admin/router/reset-auth} (or restart); the client will not retry by itself.
     */
    AUTH_FAILED,

    /**
     * The router reported a login lock ({@code lockingTime > 0}). At most one further attempt per
     * {@code router.min-relogin-interval}.
     */
    LOCKED,

    /** Network/TLS failure; retried with exponential back-off. */
    UNREACHABLE
}
