package com.wallhub.android.data.steam

import io.steamcontent.sdk.SessionState

/**
 * The one SDK session the process currently has authenticated, if any.
 *
 * The session domain and the content path deliberately run on separate engines, so that neither can close
 * the other's engine. That separation also lets both hold a live session for the same Steam account at the
 * same time - and the peer refuses the second live session for one account. Publishing the authenticated
 * engine here lets the content path borrow the session the session domain already owns instead of
 * `restore`-ing a competing one.
 *
 * A record is an engine reference plus the account name that session was authenticated for; it holds no
 * token, no event, and no session handle. It is cleared when the owning session closes, and a lender
 * re-checks the engine's own state before lending, so a stale record cannot hand out a dead session.
 */
internal object SteamContentSdkLiveSession {
    private val lock = Any()
    private var accountName: String? = null
    private var engine: SteamContentSdkEngine? = null

    /** Records that [engine] holds a session authenticated for [accountName]. */
    fun publish(
        accountName: String,
        engine: SteamContentSdkEngine,
    ) {
        synchronized(lock) {
            this.accountName = accountName
            this.engine = engine
        }
    }

    /** Forgets [engine] when it is the published one. The owning session's close path calls this. */
    fun clear(engine: SteamContentSdkEngine) {
        synchronized(lock) {
            if (this.engine === engine) {
                accountName = null
                this.engine = null
            }
        }
    }

    /**
     * The engine holding a live authenticated session for [accountName], or null when there is none.
     *
     * Null means the caller has to open and restore its own session. A record whose engine is no longer
     * authenticated is dropped rather than returned. Names are compared the way Steam compares them,
     * ignoring case.
     */
    fun authenticated(accountName: String): SteamContentSdkEngine? {
        val published =
            synchronized(lock) {
                if (!accountName.equals(this.accountName, ignoreCase = true)) return null
                engine ?: return null
            }
        if (published.state() == SessionState.AUTHENTICATED) return published
        clear(published)
        return null
    }
}
