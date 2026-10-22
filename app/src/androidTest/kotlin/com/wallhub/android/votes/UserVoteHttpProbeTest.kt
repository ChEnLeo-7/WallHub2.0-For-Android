package com.wallhub.android.votes

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Explicitly invoked read-only device probe; uses the app's existing account without copying credentials. */
class UserVoteHttpProbeTest {
    @Test
    fun knownVotesOnExistingAccount() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        // No default IDs or credentials: an explicit invocation is required.
        val up = requireNotNull(args.getString("voteUpId")?.toLongOrNull()?.takeIf { it > 0 })
        val down = requireNotNull(args.getString("voteDownId")?.toLongOrNull()?.takeIf { it > 0 })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        // Initialize the normal app-owned account restoration path, not a second login provider.
        app.startActivity(android.content.Intent(app, com.wallhub.android.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val repo = EntryPointAccessors.fromApplication(app, VoteProbeEntryPoint::class.java)
            .accountWorkshopRepository()
        try {
            assertNotNull(repo.currentAccountSteamId())
            Log.i("WallHubVoteProbe", "known-vote probe begin transport=http-test-only")
            val upResult = repo.getUserVote(up)
            Log.i("WallHubVoteProbe", "known-vote first result=$upResult")
            val downResult = repo.getUserVote(down)
            // Only ordinal results are logged. Never log exception strings, request URLs or account identity.
            Log.i("WallHubVoteProbe", "known-vote results up=$upResult down=$downResult")
            assertEquals(1, upResult)
            assertEquals(2, downResult)
        } catch (failure: Exception) {
            val stage = Regex("http-vote-probe: stage=[a-z0-9_-]+")
                .find(failure.message.orEmpty())?.value ?: "stage=account_or_bridge"
            val result = Regex("service result (-?[0-9]{1,10})")
                .find(failure.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            Log.i("WallHubVoteProbe", "known-vote probe failed $stage serviceResult=${result ?: "unknown"}")
            throw AssertionError("Controlled account vote read failed; no credentials retained")
        }
    }
}
