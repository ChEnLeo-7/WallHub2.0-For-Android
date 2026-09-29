package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamContentCredentialProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The stored sign-in is on this device but cannot be read: the record is there and the key or the ciphertext
 * is not.
 *
 * It is deliberately not "no credential". Collapsing the two is what made the download and range arms report
 * that no stored token had been read while an undecryptable one was sitting in the store, and the caller has
 * to be able to tell the user to sign in again rather than to sign in for the first time.
 */
internal class SteamContentCredentialUnreadableException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

/**
 * Serves the content credential from the store the SDK session wrote.
 *
 * The SDK owns the session and retains its **own** refresh token - [SteamContentSdkSessionBackend] persists
 * it here after a logon - so the download and range arms read that token. Handing the SDK a kSteam-minted
 * token is what the peer refuses with `AccessTokenLogon cause=RemoteClosed`, which the live account logon on
 * the worker proved is not a logon-capability gap in the SDK.
 *
 * This is the whole adapter: no protocol, no token minting, no third copy of the credential. A store that
 * holds nothing answers null, a store that holds something unreadable throws, and the caller reports that it
 * has no usable credential rather than falling back to another backend.
 */
@Singleton
internal class SteamContentSdkCredentialProvider
    @Inject
    constructor(
        private val credentialStore: EncryptedSteamCredentialStore,
    ) : SteamContentCredentialProvider {
        override suspend fun loadContentCredential(): SteamContentCredential? =
            withContext(Dispatchers.IO) {
                when (val stored = credentialStore.read()) {
                    SteamCredentialReadResult.Missing -> null

                    is SteamCredentialReadResult.Unreadable ->
                        throw SteamContentCredentialUnreadableException(
                            "The saved Steam sign-in is on this device but could not be read",
                            stored.cause,
                        )

                    is SteamCredentialReadResult.Value ->
                        stored.credential
                            .takeIf { credential -> credential.refreshToken.isNotBlank() }
                            ?.let { credential ->
                                SteamContentCredential(
                                    accountName = credential.accountName,
                                    refreshToken = credential.refreshToken,
                                )
                            }
                }
            }
    }
