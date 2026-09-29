package apk.harness.assist

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedOnCancelTest {

    /** A read that returns only once the resource is closed, as a sender's pipe that never writes. */
    private class Stuck : Closeable {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        fun read(): Int {
            entered.countDown()
            closed.await()
            throw IOException("closed")
        }
        override fun close() = closed.countDown()
    }

    @Test
    fun `a cancel closes the resource, which ends a blocked read`() = runBlocking {
        val stuck = Stuck()
        val job = launch(Dispatchers.IO) { closedOnCancel(stuck) { stuck.read() } }
        assertTrue(stuck.entered.await(2, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(2000) { job.join() }
        assertEquals(0, stuck.closed.count)
    }

    /** A resource that counts its closes. */
    private class Counted : Closeable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test
    fun `an open that returns after the cancel has its resource closed`() = runBlocking {
        val signal = Counted()
        val opened = Counted()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            openedUnlessCancelled(signal) {
                entered.countDown()
                // An open that ignores the signal and returns once it is let go.
                release.await()
                opened
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(2000) { while (signal.closes == 0) kotlinx.coroutines.delay(10) }
        release.countDown()
        withTimeout(2000) { job.join() }
        assertEquals(1, opened.closes)
    }

    @Test
    fun `an open that ends returns its resource open`() = runBlocking {
        val opened = Counted()
        assertEquals(opened, openedUnlessCancelled(Counted()) { opened })
        assertEquals(0, opened.closes)
    }

    @Test
    fun `a question not answered in time is null, even one blocked in a call that ignores the cancel`() = runBlocking {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val answer = answeredWithin(100, CoroutineScope(Dispatchers.IO)) { release.await(); 1 }
        assertNull(answer)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
        release.countDown()
    }

    @Test
    fun `a question answered in time is its answer`() = runBlocking {
        assertEquals(7, answeredWithin(2000, CoroutineScope(Dispatchers.IO)) { 7 })
    }

    @Test
    fun `a read that ends returns its value and leaves the resource open`() = runBlocking {
        val stuck = Stuck()
        assertEquals(7, closedOnCancel(stuck) { 7 })
        assertEquals(1, stuck.closed.count)
    }
}
