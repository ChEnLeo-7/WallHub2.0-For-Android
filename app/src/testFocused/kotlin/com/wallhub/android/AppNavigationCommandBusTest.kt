package com.wallhub.android

import com.wallhub.android.app.AppNavigationCommand
import com.wallhub.android.app.AppNavigationCommandBus
import com.wallhub.android.app.parseAppNavigationTarget
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppNavigationCommandBusTest {
    @After
    fun clearBus() {
        AppNavigationCommandBus.clearForTest()
    }

    @Test
    fun `warm workshop deep link becomes a workshop command`() {
        val target = parseAppNavigationTarget("wallhub://workshop/42")

        assertEquals(AppNavigationCommand.Target.Workshop(42L), target)
    }

    @Test
    fun `consumed downloads command is not replayed`() {
        AppNavigationCommandBus.accept("wallhub://downloads")
        val command = requireNotNull(AppNavigationCommandBus.command.value)
        assertTrue(command.target is AppNavigationCommand.Target.Downloads)

        AppNavigationCommandBus.consume(command.id)

        assertNull(AppNavigationCommandBus.command.value)
    }
}
