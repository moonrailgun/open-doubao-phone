package com.moonrailgun.opendoubao.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class GuardedTest {
    @Test
    fun loadsOnceAndReturnsWhatTheStoreKept() {
        val loads = AtomicInteger()
        val guarded = Guarded<List<Int>>()
        val load = { loads.incrementAndGet(); listOf(1) }
        assertEquals(listOf(1), guarded.read(load))
        assertEquals(listOf(1), guarded.read(load))
        assertEquals(1, loads.get())
        // The store may trim what it writes; readers see the trimmed copy, as they would after a restart.
        guarded.write(listOf(1, 2, 3)) { it.take(2) }
        assertEquals(listOf(1, 2), guarded.read(load))
        assertEquals(1, loads.get())
    }

    @Test
    fun failedStoreKeepsThePreviousValue() {
        val guarded = Guarded<String>()
        guarded.write("saved") { it }
        assertThrows(IllegalStateException::class.java) { guarded.write("lost") { error("disk full") } }
        assertThrows(IllegalStateException::class.java) { guarded.update({ "unused" }, { error("disk full") }) { "$it!" } }
        assertEquals("saved", guarded.read { "unused" })
    }

    @Test
    fun concurrentUpdatesAreNotLost() {
        val guarded = Guarded<Int>()
        val start = CountDownLatch(1)
        val workers = (1..8).map {
            thread {
                start.await()
                // A slow store widens the window in which an unguarded read-modify-write would lose an update.
                repeat(50) { guarded.update({ 0 }, { value -> Thread.yield(); value }) { it + 1 } }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(400, guarded.read { -1 })
    }
}
