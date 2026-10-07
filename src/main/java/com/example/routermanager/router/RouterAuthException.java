package com.example.routermanager.router;

/**
 * The router refused the login, or refuses to accept logins at all right now.
 *
 * <p>This is NEVER retried automatically inside one poll: the H188A locks the account after a few
 * failed attempts, so a retry loop would lock the household out of its own router.
 *
 * @param lockingTime seconds of lock the router reported, 0 when it reported none
 * @param credentialFailure true when the router rejected the username/password, which requires a
 *                          human (fix the credentials, then POST /api/admin/router/reset-auth)
 */
public class RouterAuthException extends RouterException {

    private final long lockingTime;
    private final boolean credentialFailure;

    public RouterAuthException(String message, long lockingTime, boolean credentialFailure) {
        super(message);
        this.lockingTime = lockingTime;
        this.credentialFailure = credentialFailure;
    }

    public long lockingTime() {
        return lockingTime;
    }

    public boolean credentialFailure() {
        return credentialFailure;
    }

    public boolean locked() {
        return lockingTime > 0;
    }
}
