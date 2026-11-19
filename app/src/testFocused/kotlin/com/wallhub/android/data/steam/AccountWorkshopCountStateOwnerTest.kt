package com.wallhub.android.data.steam

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountWorkshopCountStateOwnerTest {
    @Test
    fun `invalidation cannot interleave between generation validation and publication`() {
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val owner =
            AccountWorkshopCountStateOwner {
                publishEntered.countDown()
                check(releasePublish.await(5, TimeUnit.SECONDS))
            }
        val token = owner.begin("account-a")
        val completion = Thread { owner.complete(token, "account-a", 99, 99) }
        completion.start()
        assertTrue(publishEntered.await(5, TimeUnit.SECONDS))
        val invalidation = Thread(owner::invalidate)
        invalidation.start()

        releasePublish.countDown()
        completion.join(5_000)
        invalidation.join(5_000)

        assertFalse(completion.isAlive)
        assertFalse(invalidation.isAlive)
        assertFalse(owner.state.value.isValid)
    }

    @Test
    fun `late old account response cannot overwrite invalid or new account counts`() {
        val owner = AccountWorkshopCountStateOwner()
        val accountA = owner.begin("account-a")
        owner.invalidate()
        assertFalse(owner.state.value.isValid)
        assertFalse(owner.complete(accountA, "account-a", 99, 99))
        assertFalse(owner.state.value.isValid)

        val accountB = owner.begin("account-b")
        assertFalse(owner.complete(accountA, "account-a", 100, 100))
        assertTrue(owner.complete(accountB, "account-b", 21, 22))
        assertEquals("account-b", owner.state.value.accountName)
        assertEquals(21, owner.state.value.subscriptions)
        assertEquals(22, owner.state.value.favorites)
    }
}
