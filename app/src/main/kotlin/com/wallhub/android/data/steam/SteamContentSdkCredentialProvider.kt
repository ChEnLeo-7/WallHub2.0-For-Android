package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamContentCredentialProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Serves the content credential from the store the SDK session wrote.
 *
 * With `wallhub.useSdkSession` on, the SDK owns the session and retains its **own** refresh token -
 * [SteamContentSdkSessionBackend] persists it here after a logon - so the download and range arms have to
 * read that token instead of kSteam's live account token. Handing the SDK kSteam's token is what the peer
 * refuses with `AccessTokenLogon cause=RemoteClosed`, which the live account logon on the worker proved is
 * not a logon-capability gap in the SDK.
 *
 * This is the whole adapter: no protocol, no token minting, no third copy of the credential. An empty store
 * answers null, and the caller reports that it has no credential rather than falling back to another
 * backend.
 */
@Singleton
internal class SteamContentSdkCredentialProvider
    @Inject
    constructor(
        private val credentialStore: EncryptedSteamCredentialStore,
    ) : SteamContentCredentialProvider {
        override suspend fun loadContentCredential(): SteamContentCredential? =
            withContext(Dispatchers.IO) {
                val stored = credentialStore.load() ?: return@withContext null
                if (stored.refreshToken.isBlank()) {
                    null
                } else {
                    SteamContentCredential(
                        accountName = stored.accountName,
                        refreshToken = stored.refreshToken,
                    )
                }
            }
    }
