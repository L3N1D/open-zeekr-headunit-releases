package com.lenid.zoh.aap

import org.junit.Assert.*
import org.junit.Test

class HudCallPublisherTest {
    @Test fun leasesTimerAndHangup() {
        var now = 1000L
        val tasks = mutableSetOf<Runnable>()
        val frames = mutableListOf<HudCallPublisher.Frame>()
        val publisher = HudCallPublisher({ now }, { task, delay ->
            assertEquals(5000L, delay); tasks.add(task)
        }, { tasks.remove(it) }, { frames.add(it) })
        val ringing = ClusterCall("Caller", "123", ClusterCallState.RINGING, 0)
        publisher.update(ringing)
        publisher.update(ringing)
        assertEquals(1, frames.size)
        now += 5000
        tasks.single().run()
        assertEquals(0L, frames.last().durationMs)
        assertEquals(now, frames.last().sentAt)
        publisher.update(ringing.copy(state = ClusterCallState.ACTIVE, durationMs = 2000))
        now += 5000
        tasks.single().run()
        assertEquals(7000L, frames.last().durationMs)
        publisher.update(ringing.copy(state = ClusterCallState.HELD, durationMs = 7000))
        now += 5000
        val oldTask = tasks.single()
        oldTask.run()
        assertEquals(7000L, frames.last().durationMs)
        publisher.update(null)
        assertEquals("IDLE", frames.last().state)
        assertEquals("", frames.last().number)
        assertTrue(tasks.isEmpty())
        val count = frames.size
        oldTask.run()
        assertEquals(count, frames.size)
        assertTrue(tasks.isEmpty())
    }
}
