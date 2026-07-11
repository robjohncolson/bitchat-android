package com.bitchat.android.debug

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class DebugCommandReceiverTest {
    @After
    fun tearDown() {
        DebugConsole.setHost(null)
    }

    @Test(timeout = 10_000L)
    fun spvStopReturnsBeforeBlockingHostCompletes() {
        val hostEntered = CountDownLatch(1)
        val releaseHost = CountDownLatch(1)
        val hostFinished = CountDownLatch(1)
        val hostThread = AtomicReference<Thread>()
        val receivedCommand = AtomicReference<Pair<String, List<String>>>()

        DebugConsole.setHost(object : DebugConsole.Host {
            override fun handle(cmd: String, args: List<String>): String {
                hostThread.set(Thread.currentThread())
                receivedCommand.set(cmd to args)
                hostEntered.countDown()
                try {
                    releaseHost.await(5, TimeUnit.SECONDS)
                } finally {
                    hostFinished.countDown()
                }
                return "spv stopped"
            }
        })

        val receiverThread = Thread.currentThread()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent().putExtra("cmd", "doge-spv-stop")
        val startedAt = System.nanoTime()

        DebugCommandReceiver().onReceive(context, intent)

        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("onReceive blocked for ${elapsedMillis}ms", elapsedMillis < 1_000L)
        assertTrue("host was not dispatched", hostEntered.await(3, TimeUnit.SECONDS))
        assertFalse("host completed before being released", hostFinished.await(100, TimeUnit.MILLISECONDS))
        assertNotSame("host ran on the receiver thread", receiverThread, hostThread.get())
        assertEquals("doge-spv-stop" to emptyList<String>(), receivedCommand.get())

        releaseHost.countDown()
        assertTrue("host did not finish", hostFinished.await(3, TimeUnit.SECONDS))
    }
}
