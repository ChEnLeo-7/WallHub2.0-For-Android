package com.wallhub.android.data.steam

import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class GenerationHandleOwnerTest {
    @Test
    fun `each wrapper closes the exact generation it opened`() {
        val owner = GenerationHandleOwner<RecordingHandle>()
        val first = RecordingHandle()
        val firstWrapper = owner.replace(first)
        val second = RecordingHandle()
        val secondWrapper = owner.replace(second)

        assertEquals(1, first.closeCalls)
        assertSame(second, owner.currentValue())

        firstWrapper.close()
        assertEquals(1, first.closeCalls)
        assertSame(second, owner.currentValue())

        secondWrapper.close()
        assertEquals(1, second.closeCalls)
        assertNull(owner.currentValue())
    }

    @Test
    fun `overlapping wrapper closes never close the process owner`() {
        val owner = GenerationHandleOwner<RecordingHandle>()
        val processEngine = RecordingEngine()
        val first = owner.replace(processEngine.openSession())
        val second = owner.replace(processEngine.openSession())

        first.close()
        second.close()

        assertEquals(1, processEngine.sessions[0].closeCalls)
        assertEquals(1, processEngine.sessions[1].closeCalls)
        assertEquals(0, processEngine.closeCalls)
    }

    private class RecordingEngine : Closeable {
        val sessions = mutableListOf<RecordingHandle>()
        var closeCalls = 0

        fun openSession(): RecordingHandle = RecordingHandle().also(sessions::add)

        override fun close() {
            closeCalls += 1
        }
    }

    private class RecordingHandle : Closeable {
        var closeCalls = 0

        override fun close() {
            closeCalls += 1
        }
    }
}
